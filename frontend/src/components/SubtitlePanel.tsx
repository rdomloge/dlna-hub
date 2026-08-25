import { useCallback, useEffect, useRef, useState } from 'react';
import { getSubtitleTracks, getSubtitles } from '@/api/subtitles';
import { getStatus } from '@/api/playback';
import { parseTime } from '@/utils/formatTime';
import { useSpeech } from '@/hooks/useSpeech';
import {
  anchorFromReading,
  applyOffset,
  calibrate,
  estimate,
  findCurrentOrPreviousIndex,
  findCueIndex,
  reconcile,
  type SyncAnchor,
} from '@/utils/subtitleSync';
import type { SubtitleCue, SubtitleStatus, SubtitleTrackInfo } from '@/types/subtitles';

interface SubtitlePanelProps {
  serverId: string;
  itemId: string;
  playerId: string;
  isPlaying: boolean;
  /** Renderer position in whole seconds, straight from the status poll. */
  reportedSeconds: number;
}

/** Spacing of the calibration burst. Fine enough to pin a second boundary, brief enough to be rude only once. */
const BURST_INTERVAL_MS = 250;
const BURST_SAMPLES = 10;

/** How often to re-ask while an embedded track is still being pulled out of the container. */
const EXTRACTION_POLL_MS = 1500;

const OFFSET_STEP_MS = 250;

function offsetKey(itemId: string): string {
  return `subtitle-offset:${itemId}`;
}

function readStoredOffset(itemId: string): number {
  try {
    const raw = localStorage.getItem(offsetKey(itemId));
    const parsed = raw === null ? NaN : Number(raw);
    return Number.isFinite(parsed) ? parsed : 0;
  } catch {
    return 0;
  }
}

function trackLabel(track: SubtitleTrackInfo): string {
  if (track.source === 'SIDECAR') return 'Sidecar file';
  const parts = [track.language ? track.language.toUpperCase() : 'Unknown'];
  if (track.name) parts.push(track.name);
  if (track.forced) parts.push('forced');
  if (track.defaultTrack) parts.push('default');
  return parts.join(' · ');
}

export default function SubtitlePanel({
  serverId,
  itemId,
  playerId,
  isPlaying,
  reportedSeconds,
}: SubtitlePanelProps) {
  const [open, setOpen] = useState(false);
  const [tracks, setTracks] = useState<SubtitleTrackInfo[]>([]);
  const [trackId, setTrackId] = useState<string | null>(null);
  const [cues, setCues] = useState<SubtitleCue[]>([]);
  const [status, setStatus] = useState<SubtitleStatus>('NONE');
  const [complete, setComplete] = useState(true);
  const [cueIndex, setCueIndex] = useState(-1);
  // The panel is keyed on itemId by its parent, so a new item remounts it and this reads the
  // stored offset for that item once, on mount.
  const [offsetMs, setOffsetMs] = useState(() => readStoredOffset(itemId));
  const [showSettings, setShowSettings] = useState(false);

  const anchorRef = useRef<SyncAnchor>({ mediaMs: 0, clockMs: 0 });
  const cuesRef = useRef<SubtitleCue[]>([]);
  const offsetRef = useRef(0);
  const playingRef = useRef(isPlaying);
  const lastIndexRef = useRef(-2);
  const burstingRef = useRef(false);

  const speech = useSpeech();
  const speakRef = useRef(speech.speak);

  useEffect(() => {
    speakRef.current = speech.speak;
  }, [speech.speak]);
  useEffect(() => {
    cuesRef.current = cues;
  }, [cues]);
  useEffect(() => {
    offsetRef.current = offsetMs;
  }, [offsetMs]);
  useEffect(() => {
    playingRef.current = isPlaying;
  }, [isPlaying]);

  // ---------------------------------------------------------------- tracks

  useEffect(() => {
    if (!serverId || !itemId) return;

    let cancelled = false;
    getSubtitleTracks(serverId, itemId)
      .then((res) => {
        if (cancelled) return;
        const usable = (res.tracks || []).filter((t) => t.textBased);
        setTracks(usable);
        setTrackId(res.defaultTrackId);
      })
      .catch(() => {
        if (!cancelled) setTracks([]);
      });

    return () => {
      cancelled = true;
    };
  }, [serverId, itemId]);

  // ---------------------------------------------------------------- cues

  useEffect(() => {
    if (!open || !trackId) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const load = () => {
      getSubtitles(serverId, itemId, trackId)
        .then((res) => {
          if (cancelled) return;
          setCues(res.cues || []);
          setStatus(res.status);
          setComplete(res.complete);
          // Cues arrive in playback order, so a partial result is already showing the right
          // thing — keep asking until the container has been read to the end.
          if (!res.complete) {
            timer = setTimeout(load, EXTRACTION_POLL_MS);
          }
        })
        .catch(() => {
          if (!cancelled) setStatus('FAILED');
        });
    };
    load();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [open, serverId, itemId, trackId]);

  // ---------------------------------------------------------------- sync

  const runCalibrationBurst = useCallback(async () => {
    if (burstingRef.current || !playerId) return;
    burstingRef.current = true;
    try {
      const samples: { seconds: number; clockMs: number }[] = [];
      for (let i = 0; i < BURST_SAMPLES; i++) {
        const status = await getStatus(playerId);
        samples.push({
          seconds: parseTime(status.trackPosition || '00:00:00'),
          clockMs: performance.now(),
        });
        const anchor = calibrate(samples);
        if (anchor) {
          anchorRef.current = anchor;
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, BURST_INTERVAL_MS));
      }
      // No boundary crossed — anchor on the last reading and accept ~500ms of error.
      const last = samples[samples.length - 1];
      if (last) anchorRef.current = anchorFromReading(last);
    } catch {
      // A failed burst just leaves the previous anchor in place.
    } finally {
      burstingRef.current = false;
    }
  }, [playerId]);

  useEffect(() => {
    if (!open) return;
    void runCalibrationBurst();
  }, [open, runCalibrationBurst]);

  // Every change in the reported second is a fresh reading to check the local clock against.
  useEffect(() => {
    if (!open) return;
    const result = reconcile(
      anchorRef.current,
      { seconds: reportedSeconds, clockMs: performance.now() },
      playingRef.current
    );
    anchorRef.current = result.anchor;
    if (result.recalibrate) {
      void runCalibrationBurst();
    }
  }, [open, reportedSeconds, runCalibrationBurst]);

  // The clock runs on rAF, but state is only set when the cue actually changes — sixty
  // renders a second of identical text would flatten the phone's battery for nothing.
  useEffect(() => {
    if (!open) return;

    let frame = 0;
    const tick = () => {
      const position = applyOffset(
        estimate(anchorRef.current, performance.now(), playingRef.current),
        offsetRef.current
      );
      const index = findCurrentOrPreviousIndex(cuesRef.current, position);
      if (index !== lastIndexRef.current) {
        lastIndexRef.current = index;
        setCueIndex(index);

        const cue = cuesRef.current[index];
        // Only speak a cue that is genuinely current — not one the panel is holding on
        // screen through a gap, and not while the film is paused.
        if (
          cue &&
          playingRef.current &&
          findCueIndex(cuesRef.current, position) === index
        ) {
          speakRef.current(cue.lines.join(' '));
        }
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);

    return () => cancelAnimationFrame(frame);
  }, [open]);

  // ---------------------------------------------------------------- offset

  const nudgeOffset = (deltaMs: number) => {
    const next = offsetMs + deltaMs;
    setOffsetMs(next);
    try {
      localStorage.setItem(offsetKey(itemId), String(next));
    } catch {
      // Storage blocked — the offset still applies for this sitting.
    }
  };

  // ---------------------------------------------------------------- render

  // Nothing to offer: no button, no disabled control, no empty panel.
  if (tracks.length === 0) {
    return null;
  }

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className="w-full bg-white rounded-lg shadow-sm p-4 min-h-[60px] text-left
                   flex items-center justify-between active:bg-gray-50"
      >
        <span className="font-medium text-gray-900">Subtitles</span>
        <span className="text-sm text-gray-500">
          {tracks.length > 1 ? `${tracks.length} tracks` : 'Show'}
        </span>
      </button>
    );
  }

  const current = cueIndex >= 0 ? cues[cueIndex] : null;
  const previous = cueIndex > 0 ? cues[cueIndex - 1] : null;
  const next = cueIndex >= 0 && cueIndex + 1 < cues.length ? cues[cueIndex + 1] : cues[0];
  const extracting = !complete && status === 'EXTRACTING';

  return (
    <div className="bg-white rounded-lg shadow-sm overflow-hidden">
      <div className="flex items-center justify-between p-3 border-b">
        <span className="font-medium text-gray-900">Subtitles</span>
        <div className="flex items-center gap-2">
          {speech.supported && (
            <button
              onClick={() => (speech.enabled ? speech.disable() : speech.enable())}
              aria-label={speech.enabled ? 'Stop reading aloud' : 'Read aloud'}
              className={`min-w-[48px] min-h-[48px] rounded-lg text-lg ${
                speech.enabled ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'
              }`}
            >
              {speech.enabled ? '🔊' : '🔇'}
            </button>
          )}
          <button
            onClick={() => setShowSettings((s) => !s)}
            aria-label="Subtitle settings"
            className="min-w-[48px] min-h-[48px] rounded-lg bg-gray-100 text-gray-600"
          >
            ⚙
          </button>
          <button
            onClick={() => {
              speech.cancel();
              setOpen(false);
            }}
            aria-label="Hide subtitles"
            className="min-w-[48px] min-h-[48px] rounded-lg bg-gray-100 text-gray-600"
          >
            ✕
          </button>
        </div>
      </div>

      {/* The cue area is dark on purpose: it is read in a dark room, beside a lit television. */}
      <div className="bg-gray-900 px-4 py-5 text-center">
        <p className="text-gray-500 text-sm min-h-[1.25rem] truncate">
          {previous ? previous.lines.join(' ') : ''}
        </p>

        {/* Fixed minimum height so a one-line cue followed by a two-line cue does not make
            the whole panel jump while you are reading it. */}
        <div className="min-h-[4.5rem] flex items-center justify-center my-2">
          {current ? (
            <p className="text-white text-2xl font-medium leading-snug">
              {current.lines.map((line, i) => (
                <span key={i} className="block">
                  {line}
                </span>
              ))}
            </p>
          ) : (
            <p className="text-gray-600 text-lg">
              {extracting && cues.length === 0 ? 'Reading subtitles…' : '♪'}
            </p>
          )}
        </div>

        <p className="text-gray-500 text-sm min-h-[1.25rem] truncate">
          {next ? next.lines.join(' ') : ''}
        </p>
      </div>

      <div className="flex items-center justify-between p-3 bg-gray-50 border-t">
        <button
          onClick={() => nudgeOffset(-OFFSET_STEP_MS)}
          className="min-w-[48px] min-h-[48px] rounded-lg bg-white border text-gray-700"
          aria-label="Subtitles later"
        >
          −
        </button>
        <button
          onClick={() => nudgeOffset(-offsetMs)}
          className="text-sm text-gray-600 px-3 min-h-[48px]"
        >
          {offsetMs === 0 ? 'In sync' : `${offsetMs > 0 ? '+' : ''}${(offsetMs / 1000).toFixed(2)}s`}
        </button>
        <button
          onClick={() => nudgeOffset(OFFSET_STEP_MS)}
          className="min-w-[48px] min-h-[48px] rounded-lg bg-white border text-gray-700"
          aria-label="Subtitles sooner"
        >
          +
        </button>
      </div>

      {showSettings && (
        <div className="p-3 border-t space-y-3">
          {tracks.length > 1 && (
            <label className="block">
              <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">
                Track
              </span>
              <select
                value={trackId ?? ''}
                onChange={(e) => {
                  setCues([]);
                  lastIndexRef.current = -2;
                  setCueIndex(-1);
                  setTrackId(e.target.value);
                }}
                className="mt-1 w-full border rounded-lg p-2 min-h-[48px] text-sm"
              >
                {tracks.map((track) => (
                  <option key={track.id} value={track.id}>
                    {trackLabel(track)}
                  </option>
                ))}
              </select>
            </label>
          )}

          {speech.supported && (
            <>
              <label className="block">
                <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">
                  Reading speed — {speech.rate.toFixed(1)}×
                </span>
                <input
                  type="range"
                  min={0.8}
                  max={2}
                  step={0.1}
                  value={speech.rate}
                  onChange={(e) => speech.setRate(Number(e.target.value))}
                  className="mt-1 w-full"
                />
              </label>

              {speech.voices.length > 0 && (
                <label className="block">
                  <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">
                    Voice
                  </span>
                  <select
                    value={speech.voiceUri ?? ''}
                    onChange={(e) => speech.setVoiceUri(e.target.value || null)}
                    className="mt-1 w-full border rounded-lg p-2 min-h-[48px] text-sm"
                  >
                    <option value="">Browser default</option>
                    {speech.voices.map((voice) => (
                      <option key={voice.voiceURI} value={voice.voiceURI}>
                        {voice.name} ({voice.lang})
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </>
          )}

          <p className="text-xs text-gray-500">
            {extracting
              ? `Reading the subtitle track out of the file — ${cues.length} lines so far.`
              : `${cues.length} lines.`}
            {status === 'FAILED' && ' Could not read this track.'}
          </p>
        </div>
      )}
    </div>
  );
}

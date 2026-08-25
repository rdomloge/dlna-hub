import type { SubtitleCue } from '@/types/subtitles';

/**
 * A media position paired with the local clock reading at which it was true.
 *
 * Between polls there is no information from the renderer at all, so the position is run
 * forward locally from this anchor. `clockMs` comes from `performance.now()`, which is
 * monotonic — `Date.now()` jumps when the system clock is corrected.
 */
export interface SyncAnchor {
  mediaMs: number;
  clockMs: number;
}

export interface PositionSample {
  /** Whole seconds, as the renderer reports them. */
  seconds: number;
  clockMs: number;
}

/** How far the anchor slides per reconcile when the reading disagrees by a single second. */
const NUDGE_MS = 100;

/** Beyond this the reading is a seek, not jitter, and the anchor is rebuilt. */
const JUMP_SECONDS = 1;

/**
 * A renderer that has quietly dropped a paused stream reports 00:00:00 while still holding
 * the film. Believing it would throw the subtitles back to the opening credits.
 * See bugs/pause-then-unpause-restarts-from-beginning.
 */
const ZERO_TRAP_MS = 5000;

export function estimate(anchor: SyncAnchor, nowMs: number, playing: boolean): number {
  if (!playing) return anchor.mediaMs;
  return anchor.mediaMs + (nowMs - anchor.clockMs);
}

export function anchorFromReading(sample: PositionSample): SyncAnchor {
  return { mediaMs: sample.seconds * 1000, clockMs: sample.clockMs };
}

/**
 * Builds an anchor from a burst of closely spaced readings.
 *
 * The renderer's whole-second reading is only precise at the instant it changes: the first
 * sample to report a new second tells us the media crossed that second just before it was
 * taken. Sampling every 250ms pins that moment to a quarter of a second instead of the
 * ~500ms average error of anchoring on an arbitrary reading.
 */
export function calibrate(samples: PositionSample[]): SyncAnchor | null {
  for (let i = 1; i < samples.length; i++) {
    if (samples[i].seconds > samples[i - 1].seconds) {
      return anchorFromReading(samples[i]);
    }
  }
  return null;
}

export interface ReconcileResult {
  anchor: SyncAnchor;
  recalibrate: boolean;
}

/**
 * Checks a fresh reading against the running estimate and corrects the anchor.
 *
 * Small disagreements are nudged rather than snapped: at a second boundary the reading and
 * the estimate legitimately differ by one, and snapping every time makes cues twitch back
 * and forth. A tenth of a second per poll converges within a few seconds, invisibly.
 */
export function reconcile(
  anchor: SyncAnchor,
  reported: PositionSample,
  playing: boolean
): ReconcileResult {
  const estimated = estimate(anchor, reported.clockMs, playing);

  if (reported.seconds === 0 && estimated > ZERO_TRAP_MS) {
    return { anchor, recalibrate: false };
  }

  const estimatedSecond = Math.floor(estimated / 1000);
  const drift = reported.seconds - estimatedSecond;

  if (drift === 0) {
    return { anchor, recalibrate: false };
  }

  if (Math.abs(drift) <= JUMP_SECONDS) {
    // Lowering clockMs moves the estimate forward, and vice versa.
    const clockMs = anchor.clockMs - Math.sign(drift) * NUDGE_MS;
    return { anchor: { mediaMs: anchor.mediaMs, clockMs }, recalibrate: false };
  }

  // A seek, a skip, or a different item. Snap now so we are not badly wrong while the
  // calibration burst runs.
  return { anchor: anchorFromReading(reported), recalibrate: true };
}

/**
 * Positive offset shows cues earlier, for a subtitle file that runs behind the picture.
 * Applied at lookup time so changing it never disturbs the lock.
 */
export function applyOffset(positionMs: number, offsetMs: number): number {
  return positionMs + offsetMs;
}

/** Index of the cue covering this position, or -1 in the silence between cues. */
export function findCueIndex(cues: SubtitleCue[], positionMs: number): number {
  const index = findCurrentOrPreviousIndex(cues, positionMs);
  if (index < 0) return -1;
  return positionMs < cues[index].endMs ? index : -1;
}

/**
 * Index of the last cue starting at or before this position — what to keep on screen during
 * a gap. Returns -1 before the first cue.
 */
export function findCurrentOrPreviousIndex(cues: SubtitleCue[], positionMs: number): number {
  let low = 0;
  let high = cues.length - 1;
  let found = -1;

  while (low <= high) {
    const mid = (low + high) >> 1;
    if (cues[mid].startMs <= positionMs) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return found;
}

/** Index of the first cue starting at or after this position, or -1 past the end. */
export function findNextIndex(cues: SubtitleCue[], positionMs: number): number {
  const previous = findCurrentOrPreviousIndex(cues, positionMs);
  const next = previous + 1;
  return next < cues.length ? next : -1;
}

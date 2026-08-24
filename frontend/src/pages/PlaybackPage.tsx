import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import Header from '@/components/Header';
import {
  play,
  pause,
  stop as stopApi,
  seek as seekApi,
  forward as forwardApi,
  backward as backwardApi,
  getStatus,
  setVolume,
} from '@/api/playback';
import { useAppStore } from '@/store/useAppStore';
import { usePlaybackStore } from '@/store/usePlaybackStore';
import { useVisibility } from '@/hooks/useVisibility';
import { formatTime, parseTime } from '@/utils/formatTime';
import { cleanMediaTitle, formatSubtitle } from '@/utils/cleanMediaTitle';
import type { BrowsableItem } from '@/types/media';
import TmdbMediaPanel from '@/components/TmdbMediaPanel';

export default function PlaybackPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const selectedPlayer = useAppStore((s) => s.selectedPlayer);
  const playbackStatus = usePlaybackStore((s) => s.status);
  const setPlaybackStatus = usePlaybackStore((s) => s.setStatus);
  const isPlaying = usePlaybackStore((s) => s.isPlaying);
  const setIsPlaying = usePlaybackStore((s) => s.setIsPlaying);
  const playingPending = usePlaybackStore((s) => s.playingPending);
  const setPlayingPending = usePlaybackStore((s) => s.setPlayingPending);
  const playingPendingSince = usePlaybackStore((s) => s.playingPendingSince);
  const setPlayingPendingSince = usePlaybackStore((s) => s.setPlayingPendingSince);
  const currentTime = usePlaybackStore((s) => s.currentTime);
  const setCurrentTime = usePlaybackStore((s) => s.setCurrentTime);
  const duration = usePlaybackStore((s) => s.duration);
  const setDuration = usePlaybackStore((s) => s.setDuration);
  const volume = usePlaybackStore((s) => s.volume);
  const setVolumeState = usePlaybackStore((s) => s.setVolume);
  const reconnecting = usePlaybackStore((s) => s.reconnecting);
  const setReconnecting = usePlaybackStore((s) => s.setReconnecting);
  const activeItem = usePlaybackStore((s) => s.activeItem);
  const setActiveItem = usePlaybackStore((s) => s.setActiveItem);

  const isVisible = useVisibility();

  const [trackTitle, setTrackTitle] = useState('');
  const [trackArtist, setTrackArtist] = useState('');
  const [trackAlbum, setTrackAlbum] = useState('');
  const [thumbnailUrl] = useState('');
  const [isScrubbing, setIsScrubbing] = useState(false);
  const [playerError, setPlayerError] = useState<string | null>(null);

  const scrubRef = useRef<HTMLInputElement>(null);
  const consecutiveErrorsRef = useRef(0);
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollInFlightRef = useRef(false);
  const isStartingRef = useRef(false);
  const isScrubbingRef = useRef(false);
  const volumeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const navigationState = location.state as {
    item?: BrowsableItem;
    autoplay?: boolean;
  } | null;
  const navItem = navigationState?.item;
  const shouldAutoplay = navigationState?.autoplay === true;
  const item = navItem || activeItem;

  const [parsedSubtitle, setParsedSubtitle] = useState<string>('');
  const [tmdbSearch, setTmdbSearch] = useState<{
    title: string;
    year?: string;
    isTvHint?: boolean;
  }>({ title: '' });
  const parsedTitle = useMemo(() => cleanMediaTitle(trackTitle), [trackTitle]);

  useEffect(() => {
    if (!selectedPlayer) {
      navigate('/players');
      return;
    }
  }, [selectedPlayer, navigate]);

  useEffect(() => {
    if (!trackTitle) return;
    const parsed = cleanMediaTitle(trackTitle);
    const sameTitle = parsed.cleansedTitle === tmdbSearch.title;
    const hasNewYear = parsed.year !== undefined && parsed.year !== tmdbSearch.year;
    const hasNewTvHint = parsed.season !== undefined && tmdbSearch.isTvHint !== true;
    if (sameTitle && !hasNewYear && !hasNewTvHint) return;

    setTmdbSearch({
      title: parsed.cleansedTitle,
      year: parsed.year,
      isTvHint: parsed.season !== undefined ? true : undefined,
    });
    setParsedSubtitle(formatSubtitle(parsed.year, parsed.season, parsed.episode));
  }, [trackTitle, tmdbSearch.title, tmdbSearch.year, tmdbSearch.isTvHint]);

  useEffect(() => {
    if (!selectedPlayer) return;

    if (!navItem) {
      if (activeItem) {
        setTrackTitle(activeItem.title || '');
        setTrackArtist(activeItem.artist || '');
        setTrackAlbum(activeItem.album || '');
        const parsed = cleanMediaTitle(activeItem.title || '');
        setParsedSubtitle(formatSubtitle(parsed.year, parsed.season, parsed.episode));
        setTmdbSearch({
          title: parsed.cleansedTitle,
          year: parsed.year,
          isTvHint: parsed.season !== undefined ? true : undefined,
        });
      }
      return;
    }

    // Router state survives an iOS tab reload, so consume this one-time play request.
    navigate(location.pathname, { replace: true, state: null });

    setTrackTitle(navItem.title || '');
    setTrackArtist(navItem.artist || '');
    setTrackAlbum(navItem.album || '');
    setActiveItem(navItem);
    const parsed = cleanMediaTitle(navItem.title || '');
    setParsedSubtitle(formatSubtitle(parsed.year, parsed.season, parsed.episode));
    setTmdbSearch({
      title: parsed.cleansedTitle,
      year: parsed.year,
      isTvHint: parsed.season !== undefined ? true : undefined,
    });

    if (!shouldAutoplay || isStartingRef.current) return;
    isStartingRef.current = true;

    if (!navItem.resourceName) {
      setPlayerError('Media item has no playback URL');
      isStartingRef.current = false;
      return;
    }

    setPlayingPending(true);
    setPlayingPendingSince(Date.now());
    play(selectedPlayer.id, navItem.resourceName!, {
      title: cleanMediaTitle(navItem.title || '').cleansedTitle,
      artist: navItem.artist,
      album: navItem.album,
      duration: navItem.duration,
      mimeType: navItem.mimeType,
      protocolInfo: navItem.protocolInfo,
    })
      .then(() => {
        isStartingRef.current = false;
      })
      .catch(() => {
        setPlayerError('Failed to start playback');
        isStartingRef.current = false;
        setPlayingPending(false);
      });

  }, [navItem, shouldAutoplay, selectedPlayer, navigate, location.pathname]);

  const MAX_CONSECUTIVE_ERRORS = 3;
  const PLAY_PENDING_TIMEOUT_MS = 15000;

  const pollStatus = useCallback(async () => {
    if (!selectedPlayer || pollInFlightRef.current) return;
    pollInFlightRef.current = true;
    try {
      const status = await getStatus(selectedPlayer.id);
      consecutiveErrorsRef.current = 0;
      setPlaybackStatus(status);
      setIsPlaying(status.state === 'PLAYING');
      if (status.state === 'PLAYING') {
        setPlayingPending(false);
      }
      const pendingTimedOut = playingPending &&
        status.state !== 'PLAYING' &&
        Date.now() - playingPendingSince > PLAY_PENDING_TIMEOUT_MS;
      if (pendingTimedOut) {
        setPlayingPending(false);
        setPlayerError('Playback did not start');
      } else {
        setPlayerError(null);
      }
      if (typeof status.volume === 'number') {
        setVolumeState(status.volume);
      }

      if (!isScrubbingRef.current) {
        const pos = parseTime(status.trackPosition || '00:00:00');
        setCurrentTime(pos);

        const dur = parseTime(status.trackDuration || '00:00:00');
        setDuration(dur);
      }

      if (status.trackTitle) setTrackTitle(status.trackTitle);
      setReconnecting(false);
    } catch {
      consecutiveErrorsRef.current += 1;
      setPlayerError('Player disconnected');
      setIsPlaying(false);
      setReconnecting(false);
      if (consecutiveErrorsRef.current >= MAX_CONSECUTIVE_ERRORS) {
        setPlayingPending(false);
        navigate('/players');
      }
    } finally {
      pollInFlightRef.current = false;
    }
  }, [selectedPlayer, setPlaybackStatus, setIsPlaying, setVolumeState, setCurrentTime, setDuration, setPlayingPending, playingPending, playingPendingSince, navigate, setReconnecting]);

  const pollStatusRef = useRef(pollStatus);
  useEffect(() => {
    pollStatusRef.current = pollStatus;
  }, [pollStatus]);

  const pollIntervalMs = (isPlaying || playingPending) ? 1000 : 5000;

  useEffect(() => {
    if (!selectedPlayer || !isVisible) {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
      return;
    }

    // The interval calls through the ref, so a new pollStatus identity (which changes on
    // every playingPending transition) does not tear the interval down and re-show the
    // Connecting overlay.
    pollIntervalRef.current = setInterval(() => pollStatusRef.current(), pollIntervalMs);
    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    };
  }, [selectedPlayer, isVisible, pollIntervalMs]);

  // Fires only when the player changes or the tab becomes visible again — a real
  // (re)connection, not an ordinary play/pause transition.
  useEffect(() => {
    if (!selectedPlayer || !isVisible) return;
    setReconnecting(true);
    consecutiveErrorsRef.current = 0;
    pollStatusRef.current();
  }, [selectedPlayer, isVisible, setReconnecting]);

  const handlePlayPause = async () => {
    if (!selectedPlayer) return;
    try {
      if (isPlaying) {
        await pause(selectedPlayer.id);
        setIsPlaying(false);
        if (playbackStatus) {
          setPlaybackStatus({ ...playbackStatus, state: 'PAUSED_PLAYBACK' });
        }
      } else {
        setPlayingPending(true);
        setPlayingPendingSince(Date.now());
        if (playbackStatus?.state === 'PAUSED_PLAYBACK') {
          // Resume: the renderer still holds the URI, a bare Play is correct.
          await play(selectedPlayer.id, '');
        } else if (item?.resourceName) {
          // Restart from stopped: re-send the URI. `item` is navItem ?? activeItem, so this
          // still works after the one-time router state has been consumed.
          await play(selectedPlayer.id, item.resourceName, {
            title: cleanMediaTitle(item.title || '').cleansedTitle,
            artist: item.artist,
            album: item.album,
            duration: item.duration,
            mimeType: item.mimeType,
            protocolInfo: item.protocolInfo,
          });
        } else {
          await play(selectedPlayer.id, '');
        }
      }
    } catch {
      setPlayingPending(false);
      setPlayerError('Failed to control playback');
    }
  };

  const handleStop = async () => {
    if (!selectedPlayer) return;
    try {
      await stopApi(selectedPlayer.id);
      setIsPlaying(false);
      setPlayingPending(false);
      if (playbackStatus) {
        setPlaybackStatus({ ...playbackStatus, state: 'STOPPED' });
      }
      setCurrentTime(0);
    } catch {
      setPlayerError('Failed to stop playback');
    }
  };

  const handleForward = async () => {
    if (!selectedPlayer) return;
    try {
      await forwardApi(selectedPlayer.id);
    } catch {
      setPlayerError('Failed to skip forward');
    }
  };

  const handleBackward = async () => {
    if (!selectedPlayer) return;
    try {
      await backwardApi(selectedPlayer.id);
    } catch {
      setPlayerError('Failed to skip backward');
    }
  };

  const handleScrubChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const secs = parseInt(e.target.value);
    setCurrentTime(secs);
  };

  const handleScrubStart = () => {
    setIsScrubbing(true);
    isScrubbingRef.current = true;
  };

  const handleScrubEnd = async () => {
    isScrubbingRef.current = false;
    if (!selectedPlayer || !isScrubbing) {
      setIsScrubbing(false);
      return;
    }
    setIsScrubbing(false);
    try {
      await seekApi(selectedPlayer.id, currentTime);
    } catch {
      setPlayerError('Failed to seek');
    }
  };

  const VOLUME_DEBOUNCE_MS = 200;

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!selectedPlayer) return;
    const vol = parseInt(e.target.value, 10);
    if (Number.isNaN(vol)) return;
    // Update the slider immediately so it stays responsive, but only send the last
    // value once the user stops dragging — each send is a UPnP round-trip.
    setVolumeState(vol);
    if (volumeTimeoutRef.current) clearTimeout(volumeTimeoutRef.current);
    volumeTimeoutRef.current = setTimeout(() => {
      setVolume(selectedPlayer.id, vol).catch(() => {
        setPlayerError('Failed to set volume');
      });
    }, VOLUME_DEBOUNCE_MS);
  };

  useEffect(() => {
    return () => {
      if (volumeTimeoutRef.current) clearTimeout(volumeTimeoutRef.current);
    };
  }, []);

  if (!selectedPlayer) {
    return (
      <div className="min-h-screen bg-gray-100 flex flex-col">
        <Header title="Playback" showBack />
        <main className="flex-1 flex items-center justify-center mt-14">
          <div className="text-center py-8 text-gray-600">
            Please select a player first.
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col">
      <Header title={selectedPlayer.name} showBack />
      <main className="flex-1 overflow-y-auto px-4 py-4 mt-14 pb-8">
        {!item && !trackTitle && (
          <div className="bg-white rounded-lg shadow-sm p-6 text-center mb-4">
            <p className="text-gray-600">No media loaded</p>
            <p className="text-sm text-gray-400 mt-1">Browse media or play something on the player</p>
          </div>
        )}
        {reconnecting && (
          <div className="fixed top-16 left-1/2 -translate-x-1/2 z-50 bg-gray-900 bg-opacity-70 text-white text-xs px-3 py-1.5 rounded-full">
            Connecting
          </div>
        )}
        {playerError && (
          <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded-lg mb-4 text-center">
            {playerError}
          </div>
        )}

        <div className={`bg-white rounded-lg shadow-sm p-6 space-y-6 transition-opacity ${reconnecting ? 'opacity-40 pointer-events-none' : ''}`}>
          <div className="text-center">
            {thumbnailUrl && (
              <img
                src={thumbnailUrl}
                alt={trackTitle}
                className="w-32 h-32 object-cover rounded-lg mx-auto mb-4"
              />
            )}
            <h2 className="text-xl font-bold text-gray-900">{parsedTitle.cleansedTitle}</h2>
            {(parsedSubtitle || trackArtist || trackAlbum) && (
              <div className="mt-1 space-y-0.5">
                {parsedSubtitle && (
                  <p className="text-sm text-gray-400">{parsedSubtitle}</p>
                )}
                {trackArtist && (
                  <p className="text-gray-500">{trackArtist}</p>
                )}
                {trackAlbum && (
                  <p className="text-sm text-gray-400 mt-0.5">{trackAlbum}</p>
                )}
              </div>
            )}
          </div>

          <div className="space-y-2">
            <div className="flex justify-between text-sm text-gray-500">
              <span>{formatTime(currentTime)}</span>
              <span>{formatTime(duration)}</span>
            </div>
            <input
              ref={scrubRef}
              type="range"
              min={0}
              max={duration || 1}
              value={currentTime}
              onChange={handleScrubChange}
              onMouseDown={handleScrubStart}
              onMouseUp={handleScrubEnd}
              onTouchStart={handleScrubStart}
              onTouchEnd={handleScrubEnd}
              className="w-full h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer accent-gray-900"
            />
          </div>

          <div className="flex justify-center items-center gap-4">
            <button
              onClick={handleBackward}
              className="w-14 h-14 flex items-center justify-center bg-gray-200 rounded-full hover:bg-gray-300 transition-colors"
              aria-label="Rewind 10 seconds"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-6 w-6 text-gray-700"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M3 10h3l4-6v12l-4-6H3zm15 4h-3l-4 6V8l4 6h3z"
                />
              </svg>
            </button>

            <button
              onClick={handlePlayPause}
              className="w-16 h-16 flex items-center justify-center bg-gray-900 rounded-full hover:bg-gray-800 transition-colors"
              aria-label={isPlaying ? 'Pause' : 'Play'}
            >
              {isPlaying ? (
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  className="h-8 w-8 text-white"
                  fill="currentColor"
                  viewBox="0 0 24 24"
                >
                  <rect x="6" y="4" width="4" height="16" rx="1" />
                  <rect x="14" y="4" width="4" height="16" rx="1" />
                </svg>
              ) : (
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  className="h-8 w-8 text-white ml-1"
                  fill="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path d="M8 5v14l11-7z" />
                </svg>
              )}
            </button>

            <button
              onClick={handleStop}
              className="w-14 h-14 flex items-center justify-center bg-red-600 rounded-full hover:bg-red-700 transition-colors"
              aria-label="Stop"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-6 w-6 text-white"
                fill="currentColor"
                viewBox="0 0 24 24"
              >
                <rect x="4" y="4" width="16" height="16" rx="2" />
              </svg>
            </button>

            <button
              onClick={handleForward}
              className="w-14 h-14 flex items-center justify-center bg-gray-200 rounded-full hover:bg-gray-300 transition-colors"
              aria-label="Forward 10 seconds"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-6 w-6 text-gray-700"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M3 10h3l4-6v12l-4-6H3zm15 4h-3l-4 6V8l4 6h3z"
                />
              </svg>
            </button>
          </div>

          <div className="flex items-center gap-3">
            <svg
              xmlns="http://www.w3.org/2000/svg"
              className="h-5 w-5 text-gray-500 shrink-0"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15.536 8.464a5 5 0 010 7.072M18.364 5.636a9 9 0 010 12.728M12 12h.008v.008H12V12z"
              />
            </svg>
            <input
              type="range"
              min={0}
              max={100}
              value={volume}
              onChange={handleVolumeChange}
              className="flex-1 h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer accent-gray-900"
            />
            <span className="text-sm text-gray-500 w-8 text-right">
              {volume}
            </span>
          </div>
        </div>

        <div className="mt-4">
            <TmdbMediaPanel
              title={tmdbSearch.title}
              year={tmdbSearch.year}
              isTvHint={tmdbSearch.isTvHint}
            />
          </div>
      </main>
    </div>
  );
}

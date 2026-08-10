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

export default function PlaybackPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const selectedPlayer = useAppStore((s) => s.selectedPlayer);
  const isPlaying = usePlaybackStore((s) => s.isPlaying);
  const setIsPlaying = usePlaybackStore((s) => s.setIsPlaying);
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
  const isStartingRef = useRef(false);
  const isScrubbingRef = useRef(false);

  const navItem = (location.state as { item?: BrowsableItem } | null)?.item;
  const item = navItem || activeItem;

  const [parsedSubtitle, setParsedSubtitle] = useState<string>('');
  const parsedTitle = useMemo(() => cleanMediaTitle(trackTitle), [trackTitle]);

  useEffect(() => {
    if (!selectedPlayer) {
      navigate('/players');
      return;
    }
  }, [selectedPlayer, navigate]);

  useEffect(() => {
    if (!navItem || !selectedPlayer || isStartingRef.current) return;
    isStartingRef.current = true;

    setTrackTitle(navItem.title || '');
    setTrackArtist(navItem.artist || '');
    setTrackAlbum(navItem.album || '');
    setActiveItem(navItem);
    const parsed = cleanMediaTitle(navItem.title || '');
    setParsedSubtitle(formatSubtitle(parsed.year, parsed.season, parsed.episode));

    if (!navItem.resourceName) {
      setPlayerError('Media item has no playback URL');
      isStartingRef.current = false;
      return;
    }

    play(selectedPlayer.id, navItem.resourceName!, {
      title: cleanMediaTitle(navItem.title || '').cleansedTitle,
      artist: navItem.artist,
      album: navItem.album,
      duration: navItem.duration,
      mimeType: navItem.mimeType,
      protocolInfo: navItem.protocolInfo,
    }).catch(() => {
      setPlayerError('Failed to start playback');
    });
  }, [navItem, selectedPlayer]);

  const MAX_CONSECUTIVE_ERRORS = 3;

  const pollStatus = useCallback(async () => {
    if (!selectedPlayer) return;
    try {
      const status = await getStatus(selectedPlayer.id);
      consecutiveErrorsRef.current = 0;
      setIsPlaying(status.state === 'PLAYING');
      setVolumeState(status.volume);

      if (!isScrubbingRef.current) {
        const pos = parseTime(status.trackPosition || '00:00:00');
        setCurrentTime(pos);

        const dur = parseTime(status.trackDuration || '00:00:00');
        setDuration(dur);
      }

      if (status.trackTitle) setTrackTitle(status.trackTitle);
      setPlayerError(null);
      setReconnecting(false);
    } catch (err) {
      consecutiveErrorsRef.current += 1;
      setPlayerError('Player disconnected');
      setIsPlaying(false);
      setReconnecting(false);
      if (consecutiveErrorsRef.current >= MAX_CONSECUTIVE_ERRORS) {
        navigate('/players');
      }
    }
  }, [selectedPlayer, setIsPlaying, setVolumeState, setCurrentTime, setDuration, navigate, setReconnecting]);

  useEffect(() => {
    if (!selectedPlayer) return;

    if (isVisible) {
      setReconnecting(true);
      pollStatus();
      consecutiveErrorsRef.current = 0;
      pollIntervalRef.current = setInterval(pollStatus, 1000);
    } else {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    }

    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    };
  }, [selectedPlayer, isVisible, pollStatus, setReconnecting]);

  const handlePlayPause = async () => {
    if (!selectedPlayer) return;
    try {
      if (isPlaying) {
        await pause(selectedPlayer.id);
      } else if (navItem?.resourceName) {
        await play(selectedPlayer.id, navItem.resourceName, {
          title: cleanMediaTitle(navItem.title || '').cleansedTitle,
          artist: navItem.artist,
          album: navItem.album,
          duration: navItem.duration,
          mimeType: navItem.mimeType,
          protocolInfo: navItem.protocolInfo,
        });
      } else {
        await play(selectedPlayer.id, '');
      }
    } catch {
      setPlayerError('Failed to control playback');
    }
  };

  const handleStop = async () => {
    if (!selectedPlayer) return;
    try {
      await stopApi(selectedPlayer.id);
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

  const handleVolumeChange = async (
    e: React.ChangeEvent<HTMLInputElement>
  ) => {
    if (!selectedPlayer) return;
    const vol = parseInt(e.target.value);
    setVolumeState(vol);
    try {
      await setVolume(selectedPlayer.id, vol);
    } catch {
      setPlayerError('Failed to set volume');
    }
  };

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
          <div className="bg-yellow-100 border border-yellow-400 text-yellow-800 px-4 py-3 rounded-lg mb-4 text-center animate-pulse">
            Reconnecting...
          </div>
        )}
        {playerError && (
          <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded-lg mb-4 text-center">
            {playerError}
          </div>
        )}

        <div className="bg-white rounded-lg shadow-sm p-6 space-y-6">
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
      </main>
    </div>
  );
}

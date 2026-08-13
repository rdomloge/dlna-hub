import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { PlaybackStatus } from '@/types/playback';
import type { BrowsableItem } from '@/types/media';

interface PlaybackState {
  status: PlaybackStatus | null;
  setStatus: (status: PlaybackStatus | null) => void;
  isPlaying: boolean;
  setIsPlaying: (playing: boolean) => void;
  playingPending: boolean;
  setPlayingPending: (pending: boolean) => void;
  playingPendingSince: number;
  setPlayingPendingSince: (ts: number) => void;
  currentTime: number;
  setCurrentTime: (time: number) => void;
  duration: number;
  setDuration: (dur: number) => void;
  volume: number;
  setVolume: (vol: number) => void;
  reconnecting: boolean;
  setReconnecting: (reconnecting: boolean) => void;
  activeItem: BrowsableItem | null;
  setActiveItem: (item: BrowsableItem | null) => void;
  reset: () => void;
}

export const usePlaybackStore = create<PlaybackState>()(
  persist(
    (set) => ({
      status: null,
      setStatus: (status) => set({ status }),
      isPlaying: false,
      setIsPlaying: (playing) => set({ isPlaying: playing }),
      playingPending: false,
      setPlayingPending: (pending) => set({ playingPending: pending }),
      playingPendingSince: 0,
      setPlayingPendingSince: (ts) => set({ playingPendingSince: ts }),
      currentTime: 0,
      setCurrentTime: (time) => set({ currentTime: time }),
      duration: 0,
      setDuration: (dur) => set({ duration: dur }),
      volume: 50,
      setVolume: (vol) => set({ volume: vol }),
      reconnecting: false,
      setReconnecting: (reconnecting) => set({ reconnecting }),
      activeItem: null,
      setActiveItem: (item) => set({ activeItem: item }),
      reset: () => set({
        status: null,
        isPlaying: false,
        playingPending: false,
        playingPendingSince: 0,
        currentTime: 0,
        duration: 0,
        volume: 50,
        reconnecting: false,
        activeItem: null,
      }),
    }),
    {
      name: 'dlna-playback-state',
      partialize: (state) => ({
        isPlaying: state.isPlaying,
        currentTime: state.currentTime,
        duration: state.duration,
        volume: state.volume,
        activeItem: state.activeItem,
      }),
    }
  )
);

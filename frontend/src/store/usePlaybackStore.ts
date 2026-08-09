import { create } from 'zustand';
import type { PlaybackStatus } from '@/types/playback';

interface PlaybackState {
  status: PlaybackStatus | null;
  setStatus: (status: PlaybackStatus | null) => void;
  isPlaying: boolean;
  setIsPlaying: (playing: boolean) => void;
  currentTime: number;
  setCurrentTime: (time: number) => void;
  duration: number;
  setDuration: (dur: number) => void;
  volume: number;
  setVolume: (vol: number) => void;
}

export const usePlaybackStore = create<PlaybackState>((set) => ({
  status: null,
  setStatus: (status) => set({ status }),
  isPlaying: false,
  setIsPlaying: (playing) => set({ isPlaying: playing }),
  currentTime: 0,
  setCurrentTime: (time) => set({ currentTime: time }),
  duration: 0,
  setDuration: (dur) => set({ duration: dur }),
  volume: 50,
  setVolume: (vol) => set({ volume: vol }),
}));

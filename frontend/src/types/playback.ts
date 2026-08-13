export interface PlaybackStatus {
  state: 'STOPPED' | 'PLAYING' | 'PAUSED_PLAYBACK' | 'TRANSITIONING';
  trackTitle?: string;
  trackDuration?: string;
  trackPosition?: string;
  trackUri?: string;
  volume?: number | null;
}

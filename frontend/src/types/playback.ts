export type TransportState =
  | 'STOPPED'
  | 'PLAYING'
  | 'PAUSED_PLAYBACK'
  | 'PAUSED_RECORDING'
  | 'TRANSITIONING'
  | 'RECORDING'
  | 'NO_MEDIA_PRESENT'
  | 'UNKNOWN';

export interface PlaybackStatus {
  state: TransportState;
  trackTitle?: string;
  trackDuration?: string;
  trackPosition?: string;
  trackUri?: string;
  volume?: number | null;
}

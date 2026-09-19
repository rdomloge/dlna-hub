export type TransportState =
  | 'STOPPED'
  | 'PLAYING'
  | 'PAUSED_PLAYBACK'
  | 'PAUSED_RECORDING'
  | 'TRANSITIONING'
  | 'RECORDING'
  | 'NO_MEDIA_PRESENT'
  /** The renderer accepted the URI and is bringing the stream up. It answers no other control
   *  call in this state, which is why a status poll asks for transport state only. */
  | 'CONNECTING'
  | 'UNKNOWN';

export interface PlaybackStatus {
  state: TransportState;
  trackTitle?: string;
  trackDuration?: string;
  trackPosition?: string;
  trackUri?: string;
  volume?: number | null;
}

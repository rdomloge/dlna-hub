export interface SubtitleCue {
  index: number;
  startMs: number;
  endMs: number;
  lines: string[];
}

export type SubtitleSource = 'SIDECAR' | 'EMBEDDED';

export interface SubtitleTrackInfo {
  /** Opaque handle to pass back, e.g. "sidecar" or "embedded:3". */
  id: string;
  source: SubtitleSource;
  /** ISO-639-2 as the container reports it ("eng"); null for a sidecar, which has no metadata. */
  language: string | null;
  /** The track's own name, e.g. "SDH", when the container supplies one. */
  name: string | null;
  codec: string;
  defaultTrack: boolean;
  forced: boolean;
  /** False for bitmap formats (PGS, VobSub), which cannot be read without OCR. */
  textBased: boolean;
  english: boolean;
}

export interface SubtitleTracks {
  tracks: SubtitleTrackInfo[];
  defaultTrackId: string | null;
}

export type SubtitleStatus = 'EXTRACTING' | 'READY' | 'FAILED' | 'NONE';

export interface SubtitleCues {
  available: boolean;
  trackId: string | null;
  status: SubtitleStatus;
  /**
   * False while an embedded track is still being pulled out of the container. Cues arrive in
   * playback order, so a partial result is already usable — keep polling until this flips.
   */
  complete: boolean;
  cueCount: number;
  cues: SubtitleCue[];
  message: string | null;
}

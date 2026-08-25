import api from './axios';
import type { SubtitleCues, SubtitleTracks } from '@/types/subtitles';

/**
 * Item IDs carry `$` and `@` (e.g. `44$@38337`). Those are legal in a path segment, so the
 * other API modules pass them through — encoding here is belt-and-braces for ids we do not
 * control the shape of.
 */
function segment(value: string): string {
  return encodeURIComponent(value);
}

/**
 * Lists the subtitle tracks for an item: a sidecar `.srt` the NAS serves, plus any text
 * tracks embedded in a Matroska container. Cheap — the container's track table sits in the
 * first few kilobytes of the file.
 */
export function getSubtitleTracks(serverId: string, itemId: string): Promise<SubtitleTracks> {
  return api
    .get(`/servers/${segment(serverId)}/subtitles/${segment(itemId)}/tracks`)
    .then((res) => res.data);
}

/**
 * Fetches cues for a track, or for the best track if none is named.
 *
 * An embedded track is extracted by scanning the whole file, so the first call returns
 * `complete: false` with whatever has been found so far. Cues arrive in playback order, so
 * the opening of the film is usable within a second — poll until `complete` is true.
 */
export function getSubtitles(
  serverId: string,
  itemId: string,
  trackId?: string
): Promise<SubtitleCues> {
  return api
    .get(`/servers/${segment(serverId)}/subtitles/${segment(itemId)}`, {
      params: trackId ? { track: trackId } : undefined,
    })
    .then((res) => res.data);
}

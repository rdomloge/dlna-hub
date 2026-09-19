import api from './axios';
import type { PlaybackStatus } from '@/types/playback';

export function play(
  playerId: string,
  uri: string,
  metadata?: {
    title?: string;
    artist?: string;
    album?: string;
    duration?: string;
    mimeType?: string;
    protocolInfo?: string;
  }
): Promise<void> {
  return api
    .post(`/players/${playerId}/play`, {
      uri,
      title: metadata?.title,
      artist: metadata?.artist,
      album: metadata?.album,
      duration: metadata?.duration,
      mimeType: metadata?.mimeType,
      protocolInfo: metadata?.protocolInfo,
    })
    .then(() => undefined);
}

export function pause(playerId: string): Promise<void> {
  return api
    .post(`/players/${playerId}/pause`)
    .then(() => undefined);
}

export function stop(playerId: string): Promise<void> {
  return api
    .post(`/players/${playerId}/stop`)
    .then(() => undefined);
}

export function seek(playerId: string, seconds: number): Promise<void> {
  return api
    .post(`/players/${playerId}/seek`, { seconds })
    .then(() => undefined);
}

export function forward(playerId: string): Promise<void> {
  return api
    .post(`/players/${playerId}/forward`)
    .then(() => undefined);
}

export function backward(playerId: string): Promise<void> {
  return api
    .post(`/players/${playerId}/backward`)
    .then(() => undefined);
}

/**
 * Poll the renderer.
 *
 * `includeVolume` maps to a second UPnP service (RenderingControl), so each status call is
 * really two SOAP round-trips to the renderer. Volume only changes when someone touches the
 * TV or the Xbox remote, so the playback page asks for it occasionally rather than on every
 * poll; callers that only care about position (subtitle sync) should pass `false`.
 */
export function getStatus(playerId: string, includeVolume = true): Promise<PlaybackStatus> {
  return api
    .get(`/players/${playerId}/status`, { params: { includeVolume } })
    .then((res) => res.data);
}

export function setVolume(playerId: string, volume: number): Promise<void> {
  return api
    .put(`/players/${playerId}/volume`, { volume })
    .then(() => undefined);
}

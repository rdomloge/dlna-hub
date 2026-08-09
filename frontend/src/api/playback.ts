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

export function getStatus(playerId: string): Promise<PlaybackStatus> {
  return api.get(`/players/${playerId}/status`).then((res) => res.data);
}

export function getVolume(playerId: string): Promise<number> {
  return api
    .get(`/players/${playerId}/volume`)
    .then((res) => res.data.volume);
}

export function setVolume(playerId: string, volume: number): Promise<void> {
  return api
    .put(`/players/${playerId}/volume`, { volume })
    .then(() => undefined);
}

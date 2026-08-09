import api from './axios';
import type { MediaServer } from '@/types/server';

export function getServers(): Promise<MediaServer[]> {
  return api.get('/servers').then((res) => res.data);
}

export function subscribeToServer(id: string): Promise<void> {
  return api.post(`/servers/${id}/subscribe`).then(() => undefined);
}

export function unsubscribeFromServer(id: string): Promise<void> {
  return api.delete(`/servers/${id}/unsubscribe`).then(() => undefined);
}

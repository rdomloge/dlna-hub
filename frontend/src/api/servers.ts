import api from './axios';
import type { MediaServer } from '@/types/server';

export function getServers(): Promise<MediaServer[]> {
  return api.get('/servers').then((res) => res.data);
}

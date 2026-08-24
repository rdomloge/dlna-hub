import api from './axios';
import type { Renderer } from '@/types/player';

export function getPlayers(): Promise<Renderer[]> {
  return api.get('/players').then((res) => res.data);
}

import api from './axios';
import type { Renderer } from '@/types/player';

export function getPlayers(): Promise<Renderer[]> {
  return api.get('/players').then((res) => res.data);
}

export function getPlayer(id: string): Promise<Renderer> {
  return api.get(`/players/${id}`).then((res) => res.data);
}

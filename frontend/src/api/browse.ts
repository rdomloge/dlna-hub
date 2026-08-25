import api from './axios';
import type { BrowseResult } from '@/types/media';

export type SortOption = '' | 'dc:title' | '-dc:title' | 'dc:creator' | '-dc:creator' | 'dc:date' | '-dc:date';

export function browse(
  serverId: string,
  objectId: string = '0',
  index: number = 0,
  count: number = 50,
  sortBy: SortOption = ''
): Promise<BrowseResult> {
  return api
    .get(`/servers/${serverId}/browse`, {
      params: { objectId, index, count, sortBy },
    })
    .then((res) => res.data);
}

export function search(
  serverId: string,
  query: string,
  containerId: string = '0',
  index: number = 0,
  count: number = 50,
  sortBy: SortOption = ''
): Promise<BrowseResult> {
  return api
    .get(`/servers/${serverId}/search`, {
      params: { containerId, query, index, count, sortBy },
    })
    .then((res) => res.data);
}

export function getThumbnail(serverId: string, itemId: string): string {
  const base = import.meta.env.VITE_API_URL || '/api';
  return `${base}/servers/${serverId}/thumbnail/${itemId}`;
}

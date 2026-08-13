import api from './axios';
import type { TmdbMediaInfo, TmdbSearchResponse } from '@/types/tmdb';

export function searchTmdb(
  title: string,
  year?: string,
  isTv?: boolean,
): Promise<TmdbMediaInfo[] | null> {
  const params: Record<string, string | boolean | undefined> = { title };
  if (year) params.year = year;
  if (isTv !== undefined) params.tv = isTv;

  return api
    .get('/tmdb/search', { params })
    .then((res) => {
      const data = res.data as TmdbSearchResponse;
      if (!data.available || !data.results) {
        return null;
      }
      return data.results;
    })
    .catch(() => null);
}

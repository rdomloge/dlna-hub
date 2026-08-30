import api from './axios';
import type { BrowseResult } from '@/types/media';

export type SortOption = '' | 'dc:title' | '-dc:title' | 'dc:creator' | '-dc:creator' | 'dc:date' | '-dc:date';

export function browse(
  serverId: string,
  objectId: string = '0',
  index: number = 0,
  count: number = 50,
  sortBy: SortOption = '',
  skipEnrich: boolean = false,
  signal?: AbortSignal
): Promise<BrowseResult> {
  return api
    .get(`/servers/${serverId}/browse`, {
      params: { objectId, index, count, sortBy, skipEnrich },
      signal,
    })
    .then((res) => res.data);
}

export function search(
  serverId: string,
  query: string,
  containerId: string = '0',
  index: number = 0,
  count: number = 50,
  sortBy: SortOption = '',
  signal?: AbortSignal
): Promise<BrowseResult> {
  return api
    .get(`/servers/${serverId}/search`, {
      params: { containerId, query, index, count, sortBy },
      signal,
    })
    .then((res) => res.data);
}

export function getThumbnail(serverId: string, itemId: string): string {
  const base = import.meta.env.VITE_API_URL || '/api';
  return `${base}/servers/${serverId}/thumbnail/${itemId}`;
}

/**
 * A single date event from the effective-date stream.
 *
 * - `id` + `effectiveDate` + `complete` identify a container whose effective (latest descendant
 *   media) date was just computed; `effectiveDate` may be null when the subtree has no dates.
 * - `allDone` marks the terminal event: every container the server could date has been emitted.
 */
export interface DateStreamEvent {
  id?: string;
  effectiveDate?: string | null;
  complete?: boolean;
  allDone?: boolean;
}

/**
 * Opens the effective-date SSE stream for a folder and returns the underlying {@link EventSource}
 * so the caller can close it. The backend emits one `date` event per newly-dated container and a
 * final `allDone` event when the completion crawl finishes (or immediately for a non-date sort).
 *
 * The caller is responsible for calling {@link EventSource.close} on navigation, sort change,
 * search, server switch, or unmount.
 */
export function openDateStream(
  serverId: string,
  objectId: string,
  sortBy: SortOption,
  onEvent: (event: DateStreamEvent) => void,
  onError?: (err: Event) => void
): EventSource {
  const base = import.meta.env.VITE_API_URL || '/api';
  const url = `${base}/servers/${serverId}/browse/${encodeURIComponent(objectId)}/dates?sortBy=${encodeURIComponent(sortBy)}`;
  const source = new EventSource(url);
  source.addEventListener('date', (e: MessageEvent) => {
    try {
      onEvent(JSON.parse(e.data) as DateStreamEvent);
    } catch {
      // Malformed event; ignore rather than break the stream.
    }
  });
  if (onError) {
    source.onerror = (err) => onError(err);
  }
  return source;
}

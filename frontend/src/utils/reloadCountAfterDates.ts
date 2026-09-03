/** Upper bound the browse API accepts for `count` (BrowseController caps it at 500). */
export const MAX_BROWSE_COUNT = 500;

/**
 * How many rows to reload once the effective-date stream reports every folder dated: at least
 * one page, otherwise as many rows as are currently on screen (so the list keeps its length and
 * scroll position), capped at what the API accepts.
 */
export function reloadCountAfterDates(
  loadedCount: number,
  pageSize: number,
  maxCount: number = MAX_BROWSE_COUNT
): number {
  return Math.min(maxCount, Math.max(pageSize, loadedCount));
}

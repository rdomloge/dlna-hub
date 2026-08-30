import type { BrowsableItem } from '@/types/media';

/**
 * The instant used to date-sort an item: a container sorts by its effective (newest descendant
 * media) date when one has been computed, otherwise by its own date; a media item sorts by its
 * own date. Returns null when no date is known yet.
 */
function sortKey(item: BrowsableItem): number | null {
  const value = item.isContainer ? item.effectiveDate ?? item.date : item.date;
  if (!value) return null;
  const t = Date.parse(value);
  return Number.isNaN(t) ? null : t;
}

/**
 * Returns a new list of items sorted by date. Containers use their effective (newest-descendant
 * media) date when present, falling back to their own date; items use their own date. Items with
 * no known date yet sort to the end regardless of direction (they are filled in as the
 * effective-date stream reports them, then re-sorted).
 */
export function sortByEffectiveDate(
  items: BrowsableItem[],
  descending: boolean
): BrowsableItem[] {
  const copy = [...items];
  copy.sort((a, b) => {
    const ka = sortKey(a);
    const kb = sortKey(b);
    if (ka === null && kb === null) return 0;
    if (ka === null) return 1; // no date -> last, regardless of direction
    if (kb === null) return -1;
    return descending ? kb - ka : ka - kb;
  });
  return copy;
}

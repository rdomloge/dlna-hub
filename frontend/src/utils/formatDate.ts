/**
 * Formats an ISO/W3C datetime (or plain date) for display, e.g.
 * "2023-06-09T13:40:01Z" -> "Jun 2023" (older than this year) or "2023" (older than a year).
 * Returns null when the value is missing or unparseable.
 */
export function formatDate(value?: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (isNaN(date.getTime())) return null;

  const now = new Date();
  const sameYear = date.getFullYear() === now.getFullYear();
  if (sameYear && date.getMonth() === now.getMonth()) {
    return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  }
  if (sameYear) {
    return date.toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
  }
  return date.toLocaleDateString(undefined, { year: 'numeric' });
}

/**
 * A short "latest media" label for folders: the effective date (newest descendant media item)
 * or, for media items, their own date. Returns null when there is no date to show.
 */
export function mediaDateLabel(item: { date?: string; effectiveDate?: string }): string | null {
  const value = item.effectiveDate ?? item.date;
  return formatDate(value);
}

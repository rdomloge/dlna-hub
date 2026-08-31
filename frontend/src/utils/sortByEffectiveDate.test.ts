import { describe, it, expect } from 'vitest';
import { sortByEffectiveDate } from '@/utils/sortByEffectiveDate';
import type { BrowsableItem } from '@/types/media';

function item(id: string, container: boolean, date?: string, effectiveDate?: string): BrowsableItem {
  return {
    id,
    parentId: '0',
    title: id,
    mimeType: container ? '' : 'video/mp4',
    protocolInfo: '',
    isContainer: container,
    classType: container ? 'object.container' : 'object.item.videoItem',
    date,
    effectiveDate,
  };
}

describe('sortByEffectiveDate', () => {
  it('sorts containers by their effective (newest-descendant) date, not their own missing date', () => {
    const items = [
      item('reacher', true, undefined, '2026-08-26T20:52:43Z'),
      item('old', true, undefined, '2019-01-01T00:00:00Z'),
      item('mid', true, '2020-01-01T00:00:00Z', '2022-06-06T00:00:00Z'),
    ];
    const desc = sortByEffectiveDate(items, true);
    expect(desc.map((i) => i.id)).toEqual(['reacher', 'mid', 'old']);
  });

  it('sorts a media item by its own date', () => {
    const items = [
      item('a', false, '2020-01-01T00:00:00Z'),
      item('b', false, '2025-01-01T00:00:00Z'),
    ];
    expect(sortByEffectiveDate(items, true).map((i) => i.id)).toEqual(['b', 'a']);
    expect(sortByEffectiveDate(items, false).map((i) => i.id)).toEqual(['a', 'b']);
  });

  it('puts items with no known date last regardless of direction', () => {
    const items = [
      item('none', true),
      item('dated', true, undefined, '2021-01-01T00:00:00Z'),
      item('later', true, undefined, '2023-01-01T00:00:00Z'),
    ];
    expect(sortByEffectiveDate(items, true).map((i) => i.id)).toEqual(['later', 'dated', 'none']);
    expect(sortByEffectiveDate(items, false).map((i) => i.id)).toEqual(['dated', 'later', 'none']);
  });

  it('falls back to the container own date when it has no effective date', () => {
    const items = [
      item('a', true, '2018-01-01T00:00:00Z'),
      item('b', true, '2020-01-01T00:00:00Z'),
    ];
    expect(sortByEffectiveDate(items, true).map((i) => i.id)).toEqual(['b', 'a']);
  });

  it('does not mutate the input array', () => {
    const items = [
      item('a', false, '2020-01-01T00:00:00Z'),
      item('b', false, '2021-01-01T00:00:00Z'),
    ];
    const before = items.map((i) => i.id);
    sortByEffectiveDate(items, true);
    expect(items.map((i) => i.id)).toEqual(before);
  });
});

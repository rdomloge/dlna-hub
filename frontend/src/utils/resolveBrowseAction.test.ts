import { describe, it, expect } from 'vitest';
import { resolveBrowseAction, type BrowseAction } from './resolveBrowseAction';

const cases: { name: string; isContainer: boolean; hasRenderer: boolean; expected: BrowseAction }[] = [
  {
    name: 'container with a renderer selected -> open the folder',
    isContainer: true,
    hasRenderer: true,
    expected: 'open',
  },
  {
    // The mode this exists for: browsing with no renderer anywhere on the network.
    name: 'container with no renderer -> still opens, browsing never needs a player',
    isContainer: true,
    hasRenderer: false,
    expected: 'open',
  },
  {
    name: 'media with a renderer selected -> play it',
    isContainer: false,
    hasRenderer: true,
    expected: 'play',
  },
  {
    name: 'media with no renderer -> route to the renderer picker, never play',
    isContainer: false,
    hasRenderer: false,
    expected: 'select-renderer',
  },
];

describe('resolveBrowseAction', () => {
  for (const { name, isContainer, hasRenderer, expected } of cases) {
    it(name, () => {
      expect(resolveBrowseAction(isContainer, hasRenderer)).toBe(expected);
    });
  }

  it('never plays without a renderer, whatever the row is', () => {
    for (const isContainer of [true, false]) {
      expect(resolveBrowseAction(isContainer, false)).not.toBe('play');
    }
  });

  it('never sends a container tap to the renderer picker', () => {
    for (const hasRenderer of [true, false]) {
      expect(resolveBrowseAction(true, hasRenderer)).toBe('open');
    }
  });
});

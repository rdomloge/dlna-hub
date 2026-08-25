import { describe, it, expect } from 'vitest';
import { reconcileVolume } from './reconcileVolume';

describe('reconcileVolume', () => {
  it('trusts the renderer when nothing of ours is in flight', () => {
    expect(reconcileVolume(40, null)).toEqual({ accept: 40, pending: null });
  });

  it('ignores a stale reading while our write is in flight', () => {
    // The regression this guards: user drags to 80, a poll answers with the old 40.
    expect(reconcileVolume(40, 80)).toEqual({ accept: null, pending: 80 });
  });

  it('resumes trusting the renderer once it confirms our value', () => {
    expect(reconcileVolume(80, 80)).toEqual({ accept: 80, pending: null });
  });

  it('ignores a missing or unusable reading without dropping the pending value', () => {
    expect(reconcileVolume(undefined, 80)).toEqual({ accept: null, pending: 80 });
    expect(reconcileVolume(null, 80)).toEqual({ accept: null, pending: 80 });
    expect(reconcileVolume(NaN, 80)).toEqual({ accept: null, pending: 80 });
    expect(reconcileVolume(undefined, null)).toEqual({ accept: null, pending: null });
  });

  it('handles the boundary values the API allows', () => {
    expect(reconcileVolume(0, 0)).toEqual({ accept: 0, pending: null });
    expect(reconcileVolume(100, 100)).toEqual({ accept: 100, pending: null });
    // 0 must not be mistaken for "nothing pending".
    expect(reconcileVolume(55, 0)).toEqual({ accept: null, pending: 0 });
  });

  it('follows a drag through to the value the user released on', () => {
    // Slider dragged 40 -> 60 -> 80; only the last value is sent.
    let pending: number | null = 80;
    // Polls answered with intermediate/old readings are all ignored...
    for (const stale of [40, 55, 60, 72]) {
      const r = reconcileVolume(stale, pending);
      expect(r.accept).toBeNull();
      pending = r.pending;
    }
    // ...until the renderer reports what we actually sent.
    const settled = reconcileVolume(80, pending);
    expect(settled).toEqual({ accept: 80, pending: null });
  });
});

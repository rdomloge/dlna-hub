import { describe, it, expect } from 'vitest';
import { resolvePlayAction, type PlayAction, type PlayActionInput } from './resolvePlayAction';

const cases: { name: string; input: PlayActionInput; expected: PlayAction }[] = [
  {
    name: 'renderer still reports the pause -> resume in place',
    input: { reportedState: 'PAUSED_PLAYBACK', userPaused: true, hasResource: true },
    expected: 'resume',
  },
  {
    // The regression this function exists to prevent. The Xbox drops PAUSED_PLAYBACK a few
    // seconds after a pause but keeps the stream; restarting here loses the user's position.
    name: 'user paused but the renderer now reports STOPPED -> still resume, never restart',
    input: { reportedState: 'STOPPED', userPaused: true, hasResource: true },
    expected: 'resume',
  },
  {
    name: 'user paused and the renderer reports nothing useful -> still resume',
    input: { reportedState: undefined, userPaused: true, hasResource: true },
    expected: 'resume',
  },
  {
    name: 'user paused and the renderer reports NO_MEDIA_PRESENT -> still resume',
    input: { reportedState: 'NO_MEDIA_PRESENT', userPaused: true, hasResource: true },
    expected: 'resume',
  },
  {
    name: 'stopped by the user, item known -> restart from the beginning',
    input: { reportedState: 'STOPPED', userPaused: false, hasResource: true },
    expected: 'restart',
  },
  {
    name: 'restored session with an item but no pause -> restart from the beginning',
    input: { reportedState: undefined, userPaused: false, hasResource: true },
    expected: 'restart',
  },
  {
    name: 'stopped, no item to rebuild from -> bare play',
    input: { reportedState: 'STOPPED', userPaused: false, hasResource: false },
    expected: 'bare',
  },
  {
    name: 'paused, no item to rebuild from -> resume takes priority over bare',
    input: { reportedState: 'PAUSED_PLAYBACK', userPaused: false, hasResource: false },
    expected: 'resume',
  },
];

describe('resolvePlayAction', () => {
  for (const { name, input, expected } of cases) {
    it(name, () => {
      expect(resolvePlayAction(input)).toBe(expected);
    });
  }

  it('never restarts while the user has it paused, whatever the renderer reports', () => {
    const states = [
      'PLAYING', 'PAUSED_PLAYBACK', 'PAUSED_RECORDING', 'STOPPED',
      'TRANSITIONING', 'RECORDING', 'NO_MEDIA_PRESENT', 'UNKNOWN', undefined,
    ];
    for (const reportedState of states) {
      expect(resolvePlayAction({ reportedState, userPaused: true, hasResource: true }))
        .toBe('resume');
    }
  });
});

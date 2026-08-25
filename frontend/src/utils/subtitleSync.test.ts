import { describe, it, expect } from 'vitest';
import {
  estimate,
  anchorFromReading,
  calibrate,
  reconcile,
  applyOffset,
  findCueIndex,
  findCurrentOrPreviousIndex,
  findNextIndex,
  type SyncAnchor,
} from './subtitleSync';
import type { SubtitleCue } from '@/types/subtitles';

function cue(index: number, startMs: number, endMs: number): SubtitleCue {
  return { index, startMs, endMs, lines: [`cue ${index}`] };
}

const CUES: SubtitleCue[] = [
  cue(1, 1000, 3000),
  cue(2, 5000, 7000),
  cue(3, 7000, 9000),
];

describe('estimate', () => {
  it('advances with the clock while playing', () => {
    // given
    const anchor: SyncAnchor = { mediaMs: 10_000, clockMs: 1000 };

    // when
    const position = estimate(anchor, 3500, true);

    // then
    expect(position).toBe(12_500);
  });

  it('returns the anchor position while paused', () => {
    // given
    const anchor: SyncAnchor = { mediaMs: 10_000, clockMs: 1000 };

    // when
    const position = estimate(anchor, 99_000, false);

    // then
    expect(position).toBe(10_000);
  });
});

describe('calibrate', () => {
  it('anchors on the sample where the reported second changes', () => {
    // given — the transition is the only precise moment in a whole-second reading
    const samples = [
      { seconds: 273, clockMs: 0 },
      { seconds: 273, clockMs: 250 },
      { seconds: 274, clockMs: 500 },
      { seconds: 274, clockMs: 750 },
    ];

    // when
    const anchor = calibrate(samples);

    // then
    expect(anchor).toEqual({ mediaMs: 274_000, clockMs: 500 });
  });

  it('returns null when no transition occurs in the burst', () => {
    // given
    const samples = [
      { seconds: 100, clockMs: 0 },
      { seconds: 100, clockMs: 250 },
    ];

    // when
    const anchor = calibrate(samples);

    // then
    expect(anchor).toBeNull();
  });

  it('returns null for an empty burst', () => {
    // given

    // when

    // then
    expect(calibrate([])).toBeNull();
  });
});

describe('reconcile', () => {
  it('keeps the anchor when the reading matches the estimate', () => {
    // given — estimate is 12_500ms, i.e. second 12
    const anchor: SyncAnchor = { mediaMs: 10_000, clockMs: 1000 };

    // when
    const result = reconcile(anchor, { seconds: 12, clockMs: 3500 }, true);

    // then
    expect(result.anchor).toBe(anchor);
    expect(result.recalibrate).toBe(false);
  });

  it('nudges without jumping when the reading is one second ahead', () => {
    // given — estimate is second 12, renderer says 13
    const anchor: SyncAnchor = { mediaMs: 10_000, clockMs: 1000 };

    // when
    const result = reconcile(anchor, { seconds: 13, clockMs: 3500 }, true);

    // then — clock moved back 100ms, which moves the estimate forward by 100ms
    expect(result.anchor.clockMs).toBe(900);
    expect(result.anchor.mediaMs).toBe(10_000);
    expect(result.recalibrate).toBe(false);
  });

  it('nudges the other way when the reading is one second behind', () => {
    // given — estimate is second 12, renderer says 11
    const anchor: SyncAnchor = { mediaMs: 10_000, clockMs: 1000 };

    // when
    const result = reconcile(anchor, { seconds: 11, clockMs: 3500 }, true);

    // then
    expect(result.anchor.clockMs).toBe(1100);
    expect(result.recalibrate).toBe(false);
  });

  it('requests recalibration and snaps on a large forward jump', () => {
    // given — a skip forward
    const anchor: SyncAnchor = { mediaMs: 10_000, clockMs: 1000 };

    // when
    const result = reconcile(anchor, { seconds: 60, clockMs: 3500 }, true);

    // then
    expect(result.recalibrate).toBe(true);
    expect(result.anchor).toEqual({ mediaMs: 60_000, clockMs: 3500 });
  });

  it('requests recalibration on a large backward jump', () => {
    // given
    const anchor: SyncAnchor = { mediaMs: 600_000, clockMs: 1000 };

    // when
    const result = reconcile(anchor, { seconds: 30, clockMs: 1500 }, true);

    // then
    expect(result.recalibrate).toBe(true);
  });

  it('holds the anchor when a paused renderer reports zero mid-film', () => {
    // given — the Xbox drops a paused stream and answers 00:00:00 while still holding it.
    // See bugs/pause-then-unpause-restarts-from-beginning.
    const anchor: SyncAnchor = { mediaMs: 600_000, clockMs: 1000 };

    // when
    const result = reconcile(anchor, { seconds: 0, clockMs: 2000 }, false);

    // then
    expect(result.anchor).toBe(anchor);
    expect(result.recalibrate).toBe(false);
  });

  it('accepts a genuine zero when playback really is at the start', () => {
    // given
    const anchor: SyncAnchor = { mediaMs: 1000, clockMs: 1000 };

    // when
    const result = reconcile(anchor, { seconds: 0, clockMs: 1200 }, false);

    // then — 1000ms is inside the trap threshold, so this is treated as a real reading
    expect(result.recalibrate).toBe(false);
    expect(result.anchor.clockMs).toBe(1100);
  });
});

describe('findCueIndex', () => {
  it('returns the cue containing the position', () => {
    // given

    // when

    // then
    expect(findCueIndex(CUES, 2000)).toBe(0);
    expect(findCueIndex(CUES, 6000)).toBe(1);
  });

  it('returns -1 in the gap between cues', () => {
    // given — silence is most of a film

    // when

    // then
    expect(findCueIndex(CUES, 4000)).toBe(-1);
  });

  it('returns -1 before the first cue', () => {
    // given

    // when

    // then
    expect(findCueIndex(CUES, 500)).toBe(-1);
  });

  it('returns -1 after the last cue', () => {
    // given

    // when

    // then
    expect(findCueIndex(CUES, 50_000)).toBe(-1);
  });

  it('treats the start as inclusive and the end as exclusive', () => {
    // given — adjacent cues must not both match at the boundary

    // when

    // then
    expect(findCueIndex(CUES, 7000)).toBe(2);
    expect(findCueIndex(CUES, 3000)).toBe(-1);
  });

  it('returns -1 for an empty cue list', () => {
    // given

    // when

    // then
    expect(findCueIndex([], 1000)).toBe(-1);
  });
});

describe('findCurrentOrPreviousIndex', () => {
  it('keeps the previous cue during a gap', () => {
    // given — blanking the panel every few seconds reads as broken

    // when

    // then
    expect(findCurrentOrPreviousIndex(CUES, 4000)).toBe(0);
  });

  it('returns -1 before the first cue', () => {
    // given

    // when

    // then
    expect(findCurrentOrPreviousIndex(CUES, 0)).toBe(-1);
  });
});

describe('findNextIndex', () => {
  it('returns the upcoming cue', () => {
    // given

    // when

    // then
    expect(findNextIndex(CUES, 4000)).toBe(1);
  });

  it('returns -1 past the last cue', () => {
    // given

    // when

    // then
    expect(findNextIndex(CUES, 50_000)).toBe(-1);
  });
});

describe('applyOffset', () => {
  it('selects an earlier cue for a positive offset', () => {
    // given — positive means the subtitles lag the picture and should come sooner
    const position = 4500;

    // when
    const adjusted = applyOffset(position, 600);

    // then
    expect(adjusted).toBe(5100);
    expect(findCueIndex(CUES, adjusted)).toBe(1);
    expect(findCueIndex(CUES, position)).toBe(-1);
  });

  it('leaves the position alone at zero', () => {
    // given

    // when

    // then
    expect(applyOffset(1234, 0)).toBe(1234);
  });
});

describe('drift over time', () => {
  it('tracks a whole-second renderer to within 400ms across a minute', () => {
    // given — the actual claim the design makes. Simulates the measured Xbox behaviour:
    // whole-second readings once a second, with network jitter on when they arrive.
    const jitter = [12, 41, 7, 33, 22, 48, 3, 19, 37, 28];
    let anchor = anchorFromReading({ seconds: 0, clockMs: 0 });
    let worst = 0;

    // when
    for (let tick = 1; tick <= 60; tick++) {
      const trueMediaMs = tick * 1000 + 450;
      const arrivalClockMs = trueMediaMs + jitter[tick % jitter.length];
      const reported = { seconds: Math.floor(trueMediaMs / 1000), clockMs: arrivalClockMs };

      anchor = reconcile(anchor, reported, true).anchor;

      const estimated = estimate(anchor, arrivalClockMs, true);
      worst = Math.max(worst, Math.abs(estimated - trueMediaMs));
    }

    // then
    expect(worst).toBeLessThan(400);
  });
});

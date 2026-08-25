/**
 * What pressing "play" should actually send to the renderer.
 *
 * - `resume`  — bare `Play`. The renderer still holds the URI; it picks up where it paused.
 * - `restart` — `SetAVTransportURI` + `Play`. Rebuilds the stream, which starts at 00:00:00.
 * - `bare`    — bare `Play` with nothing better to try (no item to rebuild from).
 */
export type PlayAction = 'resume' | 'restart' | 'bare';

export interface PlayActionInput {
  /** Transport state last reported by the renderer, if any. */
  reportedState?: string;
  /** Whether the user pressed pause and has not since stopped or changed item. */
  userPaused: boolean;
  /** Whether we still know the item's playback URL. */
  hasResource: boolean;
}

/**
 * Decides between resuming a paused stream and rebuilding it from the start.
 *
 * `userPaused` is load-bearing and must not be dropped in favour of `reportedState` alone.
 * The Xbox stops reporting PAUSED_PLAYBACK a few seconds after a pause, but it has *not*
 * released the stream — a bare Play still resumes at the pause point. Deciding from the
 * reported state alone yields `restart` there, which re-sends the URI and drops the user
 * back to 00:00:00. That was a real regression; see
 * `bugs/pause-then-unpause-restarts-from-beginning/REPORT.md`.
 */
export function resolvePlayAction({
  reportedState,
  userPaused,
  hasResource,
}: PlayActionInput): PlayAction {
  if (reportedState === 'PAUSED_PLAYBACK' || userPaused) {
    return 'resume';
  }
  return hasResource ? 'restart' : 'bare';
}

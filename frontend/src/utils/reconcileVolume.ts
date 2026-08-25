/**
 * Decides whether a volume reading polled from the renderer should be trusted.
 *
 * The volume slider sends its value on a debounce (see `VOLUME_DEBOUNCE_MS`), so there is a
 * window between the user moving it and the renderer knowing about it. Status polls run every
 * second while playing, so a poll landing in that window reports the *old* volume and would
 * snap the slider backwards under the user's finger.
 *
 * The scrubber already has an equivalent guard (`isScrubbingRef`); this is the volume one.
 * Rather than a plain "busy" flag — which still loses to a poll issued before our write but
 * answered after it — this reconciles against the value we last sent: readings are ignored
 * until the renderer confirms that value, at which point we trust it again.
 */
export interface VolumeReconciliation {
  /** The value to show, or null to keep whatever is on screen. */
  accept: number | null;
  /** The new pending value: the same one, or null once the renderer has caught up. */
  pending: number | null;
}

export function reconcileVolume(
  reported: number | null | undefined,
  pending: number | null,
): VolumeReconciliation {
  if (typeof reported !== 'number' || Number.isNaN(reported)) {
    return { accept: null, pending };
  }
  if (pending === null) {
    // Nothing of ours in flight — the renderer is the source of truth.
    return { accept: reported, pending: null };
  }
  if (reported === pending) {
    // The renderer has caught up with our write; resume trusting it.
    return { accept: reported, pending: null };
  }
  // Our write is still in flight; this reading is stale.
  return { accept: null, pending };
}

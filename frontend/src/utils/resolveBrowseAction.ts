/**
 * What a tap on a browse row does.
 *
 * `open`            — descend into a container (never needs a renderer).
 * `play`            — hand the item to the selected renderer.
 * `select-renderer` — browsing without a renderer: a media tap is a request to
 *                     pick one, never an attempt to play. Routing here is what
 *                     keeps "no renderer" a supported browse mode instead of a
 *                     silent failure.
 */
export type BrowseAction = 'open' | 'play' | 'select-renderer';

/**
 * Decide the action for a tapped browse row.
 *
 * Containers always open — you can walk a library tree with nothing to play to.
 * Media needs a renderer: with one, play; without one, route to the picker.
 */
export function resolveBrowseAction(isContainer: boolean, hasRenderer: boolean): BrowseAction {
  if (isContainer) {
    return 'open';
  }
  return hasRenderer ? 'play' : 'select-renderer';
}

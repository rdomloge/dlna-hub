import { describe, it, expect } from 'vitest';

/**
 * Invariant test: the identity-aware guard + debounce-clear fix must
 * prevent items from spanning two different folders, regardless of the
 * interleaving of navigation events, debounced searches, and in-flight
 * HTTP responses.
 *
 * This tests the *guard logic* in isolation.  The full BrowsePage
 * component integrates these refs and callbacks; this test proves the
 * core invariant holds for the pattern itself.
 */

describe('BrowsePage stale-response guard', () => {
  // ---- helpers that mirror the guard logic in BrowsePage.tsx ----

  /** Simulates the guards used inside fetchItems (recency + identity). */
  function passesGuard(
    requestId: number,
    latestRequestRef: { current: number },
    activeRequestRef: { current: string },
    targetIdentity: string,
  ): boolean {
    if (requestId !== latestRequestRef.current) return false;
    if (activeRequestRef.current !== targetIdentity) return false;
    return true;
  }

  /** Simulates setItems for index===0 (clobber). */
  function setClobber(_prev: string[], items: string[]): string[] {
    return [...items];
  }

  /** Simulates setItems for index>0 (append). */
  function setAppend(prev: string[], items: string[]): string[] {
    return [...prev, ...items];
  }

  // ---- test helpers ----

  function makeItem(folder: string, idx: number): string {
    return `${folder}/item${idx}`;
  }

  // ---- tests ----

  it('drops a stale-identity page-0 response that arrives after navigating away', () => {
    // Setup
    const latestRequestRef = { current: 0 };
    const activeRequestRef = { current: '' };
    let items: string[] = [];

    // Simulate navigating to folder A, starting request #1
    latestRequestRef.current = 1;
    activeRequestRef.current = 'A\u0000';

    // Simulate response from A arriving while we're still on A
    const idA = makeItem('A', 1);
    if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setClobber(items, [idA]);
    }
    expect(items).toEqual([idA]);

    // Navigate to folder B (clears debounce + aborts in-flight)
    latestRequestRef.current = 2;
    activeRequestRef.current = 'B\u0000';

    // A's response somehow arrives late (shouldn't happen with AbortController,
    // but the guard is the safety net — test it anyway)
    // A's response would carry requestId=1
    if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setClobber(items, [idA]); // this should NOT execute
    }
    // Guard should have dropped A's response — items still contain only A's item
    expect(items).toEqual([idA]);

    // B's response arrives
    const idB = makeItem('B', 1);
    if (passesGuard(2, latestRequestRef, activeRequestRef, 'B\u0000')) {
      items = setClobber(items, [idB]);
    }
    expect(items).toEqual([idB]);
  });

  it('drops a stale-identity page-2 append that arrives after navigating away', () => {
    const latestRequestRef = { current: 0 };
    const activeRequestRef = { current: '' };
    let items: string[] = [];

    // Browse to folder A — page 0
    latestRequestRef.current = 1;
    activeRequestRef.current = 'A\u0000';
    if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setClobber(items, [makeItem('A', 1), makeItem('A', 2)]);
    }

    // Observer fires — page 2 for folder A (in flight)
    latestRequestRef.current = 2;
    activeRequestRef.current = 'A\u0000';

    // Navigate to folder B (clears debounce + aborts)
    latestRequestRef.current = 3;
    activeRequestRef.current = 'B\u0000';

    // B's page 0 arrives first
    if (passesGuard(3, latestRequestRef, activeRequestRef, 'B\u0000')) {
      items = setClobber(items, [makeItem('B', 1), makeItem('B', 2)]);
    }

    // A's page 2 arrives late (should be dropped by identity guard)
    if (passesGuard(2, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setAppend(items, [makeItem('A', 3), makeItem('A', 4)]);
    }

    // Items should NOT contain any A rows
    expect(items.filter((i) => i.startsWith('A/'))).toHaveLength(0);
    expect(items).toEqual([makeItem('B', 1), makeItem('B', 2)]);
  });

  it('prevents the debounce-timer mix: timer fires after navigation', () => {
    const latestRequestRef = { current: 0 };
    const activeRequestRef = { current: '' };
    let items: string[] = [];

    // User is on folder A and types into the search box
    latestRequestRef.current = 1;
    activeRequestRef.current = 'A\u0000query';

    // Before the debounce fires (400 ms), user navigates to folder B
    // (this is the core of the reported bug)
    latestRequestRef.current = 2;
    activeRequestRef.current = 'B\u0000';

    // The debounced callback fires late with the captured (A, 'query')
    // The identity guard must drop it
    if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000query')) {
      items = setClobber(items, [makeItem('A', 1)]); // should NOT execute
    }

    // Then B's page 0 arrives
    if (passesGuard(2, latestRequestRef, activeRequestRef, 'B\u0000')) {
      items = setClobber(items, [makeItem('B', 1)]);
    }

    // Invariant: items only contains B rows
    expect(items.filter((i) => i.startsWith('A/'))).toHaveLength(0);
    expect(items).toEqual([makeItem('B', 1)]);
  });

  it('allows same-identity requests (scrolling within same folder)', () => {
    const latestRequestRef = { current: 0 };
    const activeRequestRef = { current: '' };
    let items: string[] = [];

    // Page 0 for folder A
    latestRequestRef.current = 1;
    activeRequestRef.current = 'A\u0000';
    if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setClobber(items, [makeItem('A', 1), makeItem('A', 2)]);
    }

    // Scroll → observer fires page 2 for the same folder A
    latestRequestRef.current = 2;
    activeRequestRef.current = 'A\u0000'; // same identity
    if (passesGuard(2, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setAppend(items, [makeItem('A', 3), makeItem('A', 4)]);
    }

    expect(items).toEqual([
      makeItem('A', 1),
      makeItem('A', 2),
      makeItem('A', 3),
      makeItem('A', 4),
    ]);
  });

  it('allows a fresh request for the same folder (double-click)', () => {
    const latestRequestRef = { current: 0 };
    const activeRequestRef = { current: '' };
    let items: string[] = [];

    // First request for A
    latestRequestRef.current = 1;
    activeRequestRef.current = 'A\u0000';
    if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setClobber(items, [makeItem('A', 1)]);
    }

    // User double-clicks folder A — a new request is created with the same identity
    latestRequestRef.current = 2;
    activeRequestRef.current = 'A\u0000'; // same folder, no query
    if (passesGuard(2, latestRequestRef, activeRequestRef, 'A\u0000')) {
      items = setClobber(items, [makeItem('A', 1)]);
    }

    expect(items).toEqual([makeItem('A', 1)]);
  });
});

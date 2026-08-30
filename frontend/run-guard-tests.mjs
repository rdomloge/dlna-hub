/**
 * Minimal test runner for BrowsePage guard invariant tests.
 * Avoids esbuild/vitest so it runs in the sandbox.
 */

let passed = 0;
let failed = 0;

function assert(condition, msg) {
  if (!condition) {
    console.error(`  FAIL: ${msg}`);
    failed++;
  } else {
    passed++;
  }
}

// ---- guard logic (mirrors BrowsePage.tsx) ----
function passesGuard(
  requestId,
  latestRequestRef,
  activeRequestRef,
  targetIdentity,
) {
  if (requestId !== latestRequestRef.current) return false;
  if (activeRequestRef.current !== targetIdentity) return false;
  return true;
}

function setClobber(_prev, items) { return [...items]; }
function setAppend(prev, items) { return [...prev, ...items]; }

function makeItem(folder, idx) {
  return `${folder}/item${idx}`;
}

// ---- tests ----

function test1() {
  console.log('Test 1: drops stale-identity page-0 response after navigating away');
  const latestRequestRef = { current: 0 };
  const activeRequestRef = { current: '' };
  let items = [];

  // Navigate to A, start request #1
  latestRequestRef.current = 1;
  activeRequestRef.current = 'A\u0000';
  const idA = makeItem('A', 1);
  if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setClobber(items, [idA]);
  }
  assert(items.length === 1, 'page-0 for A applied');

  // Navigate to B
  latestRequestRef.current = 2;
  activeRequestRef.current = 'B\u0000';

  // A's stale response arrives
  if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setClobber(items, [idA]);
  }
  assert(items.length === 1 && items[0] === idA, 'stale A response dropped');

  // B's response arrives
  const idB = makeItem('B', 1);
  if (passesGuard(2, latestRequestRef, activeRequestRef, 'B\u0000')) {
    items = setClobber(items, [idB]);
  }
  assert(items.length === 1 && items[0] === idB, 'B page-0 applied');
}

function test2() {
  console.log('Test 2: drops stale-identity page-2 append after navigating away');
  const latestRequestRef = { current: 0 };
  const activeRequestRef = { current: '' };
  let items = [];

  // Page 0 for A
  latestRequestRef.current = 1;
  activeRequestRef.current = 'A\u0000';
  if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setClobber(items, [makeItem('A', 1), makeItem('A', 2)]);
  }

  // Observer fires page 2 for A (in flight)
  latestRequestRef.current = 2;
  activeRequestRef.current = 'A\u0000';

  // Navigate to B
  latestRequestRef.current = 3;
  activeRequestRef.current = 'B\u0000';

  // B's page 0 arrives
  if (passesGuard(3, latestRequestRef, activeRequestRef, 'B\u0000')) {
    items = setClobber(items, [makeItem('B', 1), makeItem('B', 2)]);
  }

  // A's page 2 arrives late
  if (passesGuard(2, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setAppend(items, [makeItem('A', 3), makeItem('A', 4)]);
  }

  const aCount = items.filter(i => i.startsWith('A/')).length;
  assert(aCount === 0, 'no A rows in items after B navigation');
  assert(items.length === 2, 'items only has B rows');
}

function test3() {
  console.log('Test 3: debounce-timer fires after navigation — guard drops it');
  const latestRequestRef = { current: 0 };
  const activeRequestRef = { current: '' };
  let items = [];

  // User types on folder A
  latestRequestRef.current = 1;
  activeRequestRef.current = 'A\u0000query';

  // Navigate to B before debounce fires
  latestRequestRef.current = 2;
  activeRequestRef.current = 'B\u0000';

  // Debounced callback fires late
  if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000query')) {
    items = setClobber(items, [makeItem('A', 1)]);
  }

  // B's page 0
  if (passesGuard(2, latestRequestRef, activeRequestRef, 'B\u0000')) {
    items = setClobber(items, [makeItem('B', 1)]);
  }

  const aCount = items.filter(i => i.startsWith('A/')).length;
  assert(aCount === 0, 'debounce timer result dropped');
  assert(items[0] === 'B/item1', 'B page-0 applied');
}

function test4() {
  console.log('Test 4: scrolling within same folder — appends work');
  const latestRequestRef = { current: 0 };
  const activeRequestRef = { current: '' };
  let items = [];

  latestRequestRef.current = 1;
  activeRequestRef.current = 'A\u0000';
  if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setClobber(items, [makeItem('A', 1), makeItem('A', 2)]);
  }

  latestRequestRef.current = 2;
  activeRequestRef.current = 'A\u0000';
  if (passesGuard(2, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setAppend(items, [makeItem('A', 3), makeItem('A', 4)]);
  }

  assert(items.length === 4, 'all 4 A items present');
  assert(items.every(i => i.startsWith('A/')), 'only A items');
}

function test5() {
  console.log('Test 5: double-click same folder — fresh request allowed');
  const latestRequestRef = { current: 0 };
  const activeRequestRef = { current: '' };
  let items = [];

  latestRequestRef.current = 1;
  activeRequestRef.current = 'A\u0000';
  if (passesGuard(1, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setClobber(items, [makeItem('A', 1)]);
  }

  latestRequestRef.current = 2;
  activeRequestRef.current = 'A\u0000';
  if (passesGuard(2, latestRequestRef, activeRequestRef, 'A\u0000')) {
    items = setClobber(items, [makeItem('A', 1)]);
  }

  assert(items.length === 1, 'same item still present after refresh');
}

// ---- run ----
test1();
test2();
test3();
test4();
test5();

console.log(`\n${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);

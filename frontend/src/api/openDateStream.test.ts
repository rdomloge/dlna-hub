import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { openDateStream } from './browse';

/**
 * A stand-in for the browser's EventSource: records the URL, the listeners, and whether it was
 * closed, and lets a test raise `date` events or an error.
 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readonly url: string;
  closed = false;
  onerror: ((e: Event) => void) | null = null;
  private listeners = new Map<string, ((e: MessageEvent) => void)[]>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, fn: (e: MessageEvent) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }

  close(): void {
    this.closed = true;
  }

  emit(type: string, data: string): void {
    for (const fn of this.listeners.get(type) ?? []) fn({ data } as MessageEvent);
  }

  fail(): void {
    this.onerror?.(new Event('error'));
  }
}

describe('openDateStream', () => {
  const realEventSource = globalThis.EventSource;

  beforeEach(() => {
    FakeEventSource.instances = [];
    globalThis.EventSource = FakeEventSource as unknown as typeof EventSource;
  });

  afterEach(() => {
    globalThis.EventSource = realEventSource;
  });

  it('closes the stream on error so the browser does not auto-reconnect, then reports it', () => {
    const onEvent = vi.fn();
    const onError = vi.fn();
    const source = openDateStream('srv', '44$13611', '-dc:date', onEvent, onError) as unknown as FakeEventSource;

    source.fail();

    expect(source.closed).toBe(true);
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onEvent).not.toHaveBeenCalled();
  });

  it('closes the stream on error even when no error handler was given', () => {
    const source = openDateStream('srv', '44$13611', '-dc:date', vi.fn()) as unknown as FakeEventSource;

    source.fail();

    expect(source.closed).toBe(true);
  });

  it('forwards parsed date events and addresses the folder in the URL', () => {
    const onEvent = vi.fn();
    const source = openDateStream('srv', '44$13611', '-dc:date', onEvent) as unknown as FakeEventSource;

    source.emit('date', JSON.stringify({ id: '44$14512', effectiveDate: '2026-08-26T20:52:43Z', complete: true }));
    source.emit('date', 'not json');

    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith({ id: '44$14512', effectiveDate: '2026-08-26T20:52:43Z', complete: true });
    expect(source.url).toContain(`/browse/${encodeURIComponent('44$13611')}/dates?sortBy=${encodeURIComponent('-dc:date')}`);
    expect(source.closed).toBe(false);
  });
});

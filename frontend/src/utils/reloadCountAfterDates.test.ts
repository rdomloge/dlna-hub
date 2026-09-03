import { describe, it, expect } from 'vitest';
import { reloadCountAfterDates } from './reloadCountAfterDates';

describe('reloadCountAfterDates', () => {
  it('reloads a full page when fewer rows than a page are on screen', () => {
    expect(reloadCountAfterDates(0, 50)).toBe(50);
    expect(reloadCountAfterDates(12, 50)).toBe(50);
  });

  it('reloads exactly the rows on screen once more than a page is loaded', () => {
    expect(reloadCountAfterDates(112, 50)).toBe(112);
  });

  it('never asks for more than the API accepts', () => {
    expect(reloadCountAfterDates(5000, 50)).toBe(500);
    expect(reloadCountAfterDates(5000, 50, 200)).toBe(200);
  });
});

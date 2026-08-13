import { describe, expect, it } from 'vitest';
import { cleanMediaTitle, formatSubtitle } from './cleanMediaTitle';

interface TestCase {
  input: string;
  cleansedTitle: string;
  year?: string;
  season?: number;
  episode?: number;
}

const cases: TestCase[] = [
  {
    input: 'A.Fish.Called.Wanda.1988.1080p.BluRay.x264-[YTS.AM]',
    cleansedTitle: 'A Fish Called Wanda',
    year: '1988',
  },
  {
    input: 'Succession.S01E01.1080p.WEB-DL.x265-GROUP.mkv',
    cleansedTitle: 'Succession',
    season: 1,
    episode: 1,
  },
  {
    input: '1917.2019.1080p',
    cleansedTitle: '1917',
    year: '2019',
  },
  {
    input: '2001.A.Space.Odyssey.1968.1080p',
    cleansedTitle: '2001 A Space Odyssey',
    year: '1968',
  },
  { input: 'Next.2007', cleansedTitle: 'Next', year: '2007' },
  {
    input: 'Full.Metal.Jacket.1987',
    cleansedTitle: 'Full Metal Jacket',
    year: '1987',
  },
  { input: 'Spider-Man.2002', cleansedTitle: 'Spider-Man', year: '2002' },
  { input: 'Wall-E.2008', cleansedTitle: 'Wall-E', year: '2008' },
  {
    input: 'Charlottes.Web.2006',
    cleansedTitle: 'Charlottes Web',
    year: '2006',
  },
  {
    input: 'Game.of.Thrones.S1.E2.1080p.WEB-DL',
    cleansedTitle: 'Game of Thrones',
    season: 1,
    episode: 2,
  },
  {
    input: 'Stranger_Things_S01 E02_1080p.mkv',
    cleansedTitle: 'Stranger Things',
    season: 1,
    episode: 2,
  },
  {
    input: 'Friends.1x02.720p.mp4',
    cleansedTitle: 'Friends',
    season: 1,
    episode: 2,
  },
  {
    input: 'The.Office.S01E103.1080p',
    cleansedTitle: 'The Office',
    season: 1,
    episode: 103,
  },
  {
    input: 'Inception.2010.1080p.BluRay.x264.HDR10+',
    cleansedTitle: 'Inception',
    year: '2010',
  },
  {
    input: 'Dune.2021.4K.UHD.BluRay.x265.IMAX-Enhanced',
    cleansedTitle: 'Dune',
    year: '2021',
  },
  { input: 'Opus.2022.1080p.BluRay', cleansedTitle: 'Opus', year: '2022' },
  { input: 'UC.2021.1080p.WEB-DL', cleansedTitle: 'UC', year: '2021' },
  { input: 'Web.2020.1080p.BluRay', cleansedTitle: 'Web', year: '2020' },
  { input: 'Full.2019.1080p.WEB-DL', cleansedTitle: 'Full', year: '2019' },
  {
    input: 'The.Shawshank.Redemption(1994).1080p',
    cleansedTitle: 'The Shawshank Redemption',
    year: '1994',
  },
  {
    input: 'Forrest.Gump[1994].1080p',
    cleansedTitle: 'Forrest Gump',
    year: '1994',
  },
  {
    input: 'Pulp.Fiction.1994.1080p.BluRay.x264-SPARKS',
    cleansedTitle: 'Pulp Fiction',
    year: '1994',
  },
  { input: 'Mr Hollands Opus', cleansedTitle: 'Mr Hollands Opus' },
  { input: 'Spider-Man', cleansedTitle: 'Spider-Man' },
  { input: '1917', cleansedTitle: '1917' },
  { input: '', cleansedTitle: '' },
];

describe('cleanMediaTitle', () => {
  it.each(cases)('parses $input', ({ input, ...expected }) => {
    expect(cleanMediaTitle(input)).toEqual({
      ...expected,
      year: expected.year,
      season: expected.season,
      episode: expected.episode,
    });
  });
});

describe('formatSubtitle', () => {
  it('formats episode information before the year', () => {
    expect(formatSubtitle('2020', 1, 2)).toBe('S1 E2 • 2020');
  });
});

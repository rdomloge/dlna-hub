export interface ParsedMediaInfo {
  cleansedTitle: string;
  year?: string;
  season?: number;
  episode?: number;
}

const MEDIA_EXTENSION_PATTERN = /\.(?:3g2|3gp|avi|divx|flv|iso|m2ts|m4v|mkv|mov|mp4|mpeg|mpg|mts|ogv|ts|vob|webm|wmv)$/i;
const YEAR_TOKEN_PATTERN = /^(?:19|20)\d{2}$/;
const COMBINED_EPISODE_PATTERN = /^S(\d{1,2})E(\d{1,3})$/i;
const X_EPISODE_PATTERN = /^(\d{1,2})X(\d{1,3})$/i;
const SEASON_PATTERN = /^S(\d{1,2})$/i;
const EPISODE_PATTERN = /^E(\d{1,3})$/i;

const TECHNICAL_TOKEN_PATTERNS: RegExp[] = [
  /^(?:360p|480[pi]|576p|720[pi]|1080[pi]|1440p|2160p|4k|8k)$/i,
  /^(?:Blu-?Ray|BDRip|BRRip|DVDRip|DVDscr|HDTV|HDRip|UHD|WEB-?DL|WEBRip)$/i,
  /^(?:AV1|AVC|DivX|H[._-]?264|H[._-]?265|HEVC|VP9|Xvid|x264|x265)$/i,
  /^(?:AAC|AC3|Atmos|DTS(?:-HD)?|EAC3|FLAC|MP3|Opus|TrueHD)$/i,
  /^(?:Dolby-?Vision|DV|HDR|HDR10\+?|HLG|IMAX(?:-Enhanced)?|SDR)$/i,
  /^(?:Director'?s-?Cut|Extended|Limited|Remastered|Unrated)$/i,
];

const STRONG_TECHNICAL_TOKEN_PATTERNS: RegExp[] = [
  TECHNICAL_TOKEN_PATTERNS[0],
  TECHNICAL_TOKEN_PATTERNS[1],
  TECHNICAL_TOKEN_PATTERNS[2],
  TECHNICAL_TOKEN_PATTERNS[4],
];

export function cleanMediaTitle(rawTitle: string): ParsedMediaInfo {
  if (!rawTitle) {
    return { cleansedTitle: '' };
  }

  const fallbackTitle = rawTitle.trim();
  let title = fallbackTitle.replace(MEDIA_EXTENSION_PATTERN, '');

  const trailingGroup = title.match(/(?:\s*[-._]\s*)?\[([^\]]+)]\s*$/);
  if (trailingGroup && !YEAR_TOKEN_PATTERN.test(trailingGroup[1].trim())) {
    title = title.slice(0, trailingGroup.index ?? title.length).trim();
  }

  title = title.replace(/[([]((?:19|20)\d{2})[)\]]/g, '.$1');

  const tokens = title
    .split(/[._\s]+/)
    .map((token) => token.replace(/^[([{]+|[)\]}]+$/g, ''))
    .filter(Boolean);

  let season: number | undefined;
  let episode: number | undefined;
  let episodeStart = -1;

  for (let index = 0; index < tokens.length; index += 1) {
    const combinedMatch = tokens[index].match(COMBINED_EPISODE_PATTERN);
    const xMatch = tokens[index].match(X_EPISODE_PATTERN);

    if (combinedMatch || xMatch) {
      const match = combinedMatch || xMatch!;
      season = parseInt(match[1], 10);
      episode = parseInt(match[2], 10);
      episodeStart = index;
      break;
    }

    const seasonMatch = tokens[index].match(SEASON_PATTERN);
    const episodeMatch = tokens[index + 1]?.match(EPISODE_PATTERN);
    if (seasonMatch && episodeMatch) {
      season = parseInt(seasonMatch[1], 10);
      episode = parseInt(episodeMatch[1], 10);
      episodeStart = index;
      break;
    }
  }

  let year: string | undefined;
  let yearIndex = -1;
  for (let index = tokens.length - 1; index > 0; index -= 1) {
    if (YEAR_TOKEN_PATTERN.test(tokens[index])) {
      year = tokens[index];
      yearIndex = index;
      break;
    }
  }

  let titleEnd = tokens.length;
  if (yearIndex >= 0) titleEnd = yearIndex;
  if (episodeStart >= 0) titleEnd = Math.min(titleEnd, episodeStart);
  if (yearIndex < 0 && episodeStart < 0) {
    titleEnd = findTechnicalSuffixStart(tokens);
  }

  const cleansedTitle = tokens.slice(0, titleEnd).join(' ').trim();

  return {
    cleansedTitle: cleansedTitle || fallbackTitle,
    year,
    season,
    episode,
  };
}

function findTechnicalSuffixStart(tokens: string[]): number {
  let index = tokens.length - 1;
  if (
    index > 0 &&
    isReleaseGroup(tokens[index]) &&
    isTechnicalSuffix(tokens[index - 1])
  ) {
    index -= 1;
  }

  const suffixEnd = index;
  let technicalCount = 0;
  let hasStrongToken = false;
  while (index >= 0 && isTechnicalSuffix(tokens[index])) {
    technicalCount += 1;
    hasStrongToken ||= isStrongTechnicalSuffix(tokens[index]);
    index -= 1;
  }

  if (technicalCount === 0 || (!hasStrongToken && technicalCount < 2)) {
    return tokens.length;
  }
  return suffixEnd - technicalCount + 1;
}

function isTechnicalSuffix(token: string): boolean {
  return TECHNICAL_TOKEN_PATTERNS.some((pattern) => pattern.test(token)) ||
    splitTechnicalGroup(token, isTechnicalSuffix);
}

function isStrongTechnicalSuffix(token: string): boolean {
  return STRONG_TECHNICAL_TOKEN_PATTERNS.some((pattern) => pattern.test(token)) ||
    splitTechnicalGroup(token, isStrongTechnicalSuffix);
}

function splitTechnicalGroup(
  token: string,
  isTechnical: (value: string) => boolean,
): boolean {
  const separator = token.lastIndexOf('-');
  return separator > 0 &&
    isReleaseGroup(token.slice(separator + 1)) &&
    isTechnical(token.slice(0, separator));
}

function isReleaseGroup(token: string): boolean {
  return /^[A-Z0-9]{2,20}$/.test(token);
}

export function formatSubtitle(year?: string, season?: number, episode?: number): string {
  const parts: string[] = [];
  if (season !== undefined && episode !== undefined) {
    parts.push(`S${season} E${episode}`);
  }
  if (year) {
    parts.push(year);
  }
  return parts.join(' • ');
}

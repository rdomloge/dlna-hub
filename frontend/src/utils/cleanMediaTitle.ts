export interface ParsedMediaInfo {
  cleansedTitle: string;
  year?: string;
  season?: number;
  episode?: number;
}

const NOISE_PATTERNS: RegExp[] = [
  /(1080p|720p|480p|4k|2160p|576p|360p)/gi,
  /(BluRay|Blu-ray|BDRip|BluRay|UHD|HDRip|HC|HDTV|DTV|WEB-DL|WEBRip|WEBDL|WEB|DSR|PDTV|SATRip|DSRip|TVRip)/gi,
  /(x264|x265|h264|h265|HEVC|AVC|AAC|AC3|DTS|DD5\.1|DD\+|DDP|TrueHD|Atmos|FLAC|Opus|Vorbis|eac3|mp3)/gi,
  /(REMASTERED|DIRECTOR'?S ?CUT|UNRATED|EXTENDED|DC|UC|EXT|LIMITED|COMPLETE|SEASON|FULL)/gi,
  /(HDR|HDR10|HDR10\+|Dolby ?Vision|DV|D-Vision|3D|SDR|HLG)/gi,
  /(Dolby.?Digital|Dolby.?Atmos|Dolby.?Surround)/gi,
  /^(ENG|ESP|LAT|LATINO|DUAL|MULTI|SUBBED)$/i,
  /^-\s*(.+)$/i,
  /-\s*([A-Z][a-zA-Z0-9]+)$/gi,
  /\[([^\]]+)\]$/gi,
  /\(([^)]+)\)$/,
  /^(19|20)\d{2}$/.source,
  /(IMAX|IMAX-Enhanced)/gi,
];

const YEAR_PATTERN = /(?:^|\s|\.|[-_])(19|20)\d{2}(?:\s|\.|[-_]|$)/;
const SE_EP_MATCH_PATTERN = /S(\d{1,2})E(\d{1,2})/i;
const SE_EP_STRIP_PATTERN = /S\d{1,2}E\d{1,2}/gi;

export function cleanMediaTitle(rawTitle: string): ParsedMediaInfo {
  if (!rawTitle) {
    return { cleansedTitle: '' };
  }

  let title = rawTitle.trim();

  let year: string | undefined;
  const yearMatch = title.match(YEAR_PATTERN);
  if (yearMatch) {
    year = yearMatch[0].trim();
  }

  let season: number | undefined;
  let episode: number | undefined;
  const seEpMatch = title.match(SE_EP_MATCH_PATTERN);
  if (seEpMatch) {
    season = parseInt(seEpMatch[1], 10);
    episode = parseInt(seEpMatch[2], 10);
  }

  for (const pattern of NOISE_PATTERNS) {
    title = title.replace(pattern, '');
  }

  title = title
    .replace(/[.\-_]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  if (year) {
    title = title.replace(new RegExp(`\\b${year}\\b`, 'g'), '').trim();
  }

  if (season !== undefined && episode !== undefined) {
    title = title.replace(SE_EP_STRIP_PATTERN, '').trim();
  }

  title = title
    .replace(/[.\-_]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  if (title.startsWith('.') || title.startsWith('-') || title.startsWith('_')) {
    title = title.slice(1).trim();
  }
  if (title.endsWith('.')) {
    title = title.slice(0, -1).trim();
  }

  return {
    cleansedTitle: title || rawTitle,
    year,
    season,
    episode,
  };
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

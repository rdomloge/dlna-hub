export interface CastMember {
  name: string;
  character: string;
  profilePath: string | null;
  profileUrl: string | null;
  order: number;
}

export interface CrewMember {
  name: string;
  job: string;
  department: string;
  profilePath: string | null;
  profileUrl: string | null;
}

export interface TmdbMediaInfo {
  tmdbId: string;
  type: 'movie' | 'tv';
  title: string;
  overview: string | null;
  tagline: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  posterUrl: string | null;
  backdropUrl: string | null;
  releaseYear: string | null;
  genres: string[];
  runtime: string | null;
  cast: CastMember[];
  crew: CrewMember[];
}

export interface TmdbSearchResponse {
  available: boolean;
  found?: boolean;
  results?: TmdbMediaInfo[];
}

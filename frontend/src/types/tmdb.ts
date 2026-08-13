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
  overview: string;
  tagline: string;
  posterPath: string | null;
  backdropPath: string | null;
  posterUrl: string | null;
  backdropUrl: string | null;
  releaseYear: string;
  genres: string[];
  runtime: string;
  cast: CastMember[];
  crew: CrewMember[];
}

export interface TmdbSearchResponse {
  available: boolean;
  found?: boolean;
  results?: TmdbMediaInfo[];
}

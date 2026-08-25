import { useState, useEffect } from 'react';
import { searchTmdb } from '@/api/tmdb';
import type { TmdbMediaInfo } from '@/types/tmdb';

interface TmdbMediaPanelProps {
  title: string;
  year?: string;
  isTvHint?: boolean;
}

export default function TmdbMediaPanel({ title, year, isTvHint }: TmdbMediaPanelProps) {
  const [results, setResults] = useState<TmdbMediaInfo[]>([]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!title) {
      // Data-fetch effect: clears the panel state when the title goes away; the
      // searchTmdb fetch below is the effect's external-system sync.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setResults([]);
      setSelectedIndex(0);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);

    searchTmdb(title, year, isTvHint).then((res) => {
      if (!cancelled) {
        setResults(res || []);
        setSelectedIndex(0);
        setLoading(false);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [title, year, isTvHint]);

  if (loading) {
    return (
      <div className="bg-white rounded-lg shadow-sm p-6">
        <div className="animate-pulse flex space-x-4">
          <div className="h-32 w-24 bg-gray-200 rounded" />
          <div className="flex-1 space-y-3 py-1">
            <div className="h-4 bg-gray-200 rounded w-3/4" />
            <div className="h-3 bg-gray-200 rounded w-1/2" />
            <div className="h-3 bg-gray-200 rounded" />
          </div>
        </div>
      </div>
    );
  }

  if (results.length === 0) {
    return null;
  }

  const selected = results[selectedIndex];
  const directors = selected.crew.filter((c) => c.job === 'Director');
  const writers = selected.crew.filter((c) => ['Writer', 'Screenplay', 'Story'].includes(c.job));

  return (
    <div className="bg-white rounded-lg shadow-sm overflow-hidden">
      {results.length > 1 && (
        <div className="p-3 bg-gray-50 border-b">
          <p className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-2">
            Match ({results.length})
          </p>
          <div className="flex gap-2 overflow-x-auto pb-1">
            {results.map((r, i) => (
              <button
                key={r.tmdbId}
                onClick={() => setSelectedIndex(i)}
                className={`flex-shrink-0 w-20 rounded-lg overflow-hidden border-2 transition-all ${
                  i === selectedIndex
                    ? 'border-gray-900 ring-1 ring-gray-900'
                    : 'border-transparent opacity-60 hover:opacity-100'
                }`}
              >
                {r.posterUrl ? (
                  <img
                    src={r.posterUrl}
                    alt={r.title}
                    className="w-full h-28 object-cover"
                  />
                ) : (
                  <div className="w-full h-28 bg-gray-200 flex items-center justify-center">
                    <span className="text-xs text-gray-400">{r.type}</span>
                  </div>
                )}
              </button>
            ))}
          </div>
        </div>
      )}

      {selected.backdropUrl && (
        <div className="relative h-32 sm:h-40">
          <img
            src={selected.backdropUrl}
            alt={selected.title}
            className="w-full h-full object-cover"
          />
          <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent" />
        </div>
      )}

      <div className="p-4 sm:p-6">
        <div className="flex gap-4">
          {selected.posterUrl && (
            <img
              src={selected.posterUrl}
              alt={selected.title}
              className="w-20 h-30 sm:w-24 sm:h-36 object-cover rounded-lg shrink-0 shadow-md"
            />
          )}

          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <h3 className="text-lg font-bold text-gray-900 truncate">{selected.title}</h3>
              <span className="text-xs font-medium text-gray-400 uppercase bg-gray-100 px-1.5 py-0.5 rounded">
                {selected.type}
              </span>
            </div>

            <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-gray-500">
              {selected.releaseYear && (
                <span>{selected.releaseYear}</span>
              )}
              {selected.genres.length > 0 && (
                <>
                  <span className="text-gray-300">{selected.genres.slice(0, 3).join(', ')}</span>
                </>
              )}
              {selected.runtime && (
                <>
                  <span className="text-gray-300">{selected.runtime}</span>
                </>
              )}
            </div>

            {selected.tagline && (
              <p className="mt-2 text-sm text-gray-400 italic">"{selected.tagline}"</p>
            )}
          </div>
        </div>

        {selected.overview && (
          <div className="mt-4">
            <p className="text-sm text-gray-600 leading-relaxed">{selected.overview}</p>
          </div>
        )}

        {(directors.length > 0 || writers.length > 0) && (
          <div className="mt-4 border-t pt-4">
            <h4 className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-2">Crew</h4>
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
              {directors.length > 0 && (
                <span className="text-gray-600">
                  <span className="text-gray-400">Director: </span>
                  {directors.map((d) => d.name).join(', ')}
                </span>
              )}
              {writers.length > 0 && (
                <span className="text-gray-600">
                  <span className="text-gray-400">Writer: </span>
                  {writers.map((w) => w.name).join(', ')}
                </span>
              )}
            </div>
          </div>
        )}

        {selected.cast.length > 0 && (
          <div className="mt-4 border-t pt-4">
            <h4 className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-3">Cast</h4>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {selected.cast.slice(0, 6).map((member) => (
                <div key={member.name} className="flex items-center gap-2">
                  {member.profileUrl ? (
                    <img
                      src={member.profileUrl}
                      alt={member.name}
                      className="w-8 h-8 rounded-full object-cover shrink-0"
                    />
                  ) : (
                    <div className="w-8 h-8 rounded-full bg-gray-200 shrink-0" />
                  )}
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-gray-700 truncate">{member.name}</p>
                    {member.character && (
                      <p className="text-xs text-gray-400 truncate">{member.character}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

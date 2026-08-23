import { useEffect, useState, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import Header from '@/components/Header';
import LoadingSpinner from '@/components/LoadingSpinner';
import { browse as browseApi, search as searchApi, type SortOption } from '@/api/browse';
import { useAppStore } from '@/store/useAppStore';
import type { BrowsableItem } from '@/types/media';
import { mediaDateLabel } from '@/utils/formatDate';

const PAGE_SIZE = 50;
const SEARCH_DEBOUNCE_MS = 400;

const mediaType = (mimeType: string | null | undefined): 'video' | 'audio' | 'image' | 'other' => {
  if (!mimeType) return 'other';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType.startsWith('audio/')) return 'audio';
  if (mimeType.startsWith('image/')) return 'image';
  return 'other';
};

const typeLabel = (t: string): string => {
  switch (t) {
    case 'video':
      return 'Video';
    case 'audio':
      return 'Audio';
    case 'image':
      return 'Image';
    default:
      return 'File';
  }
};

const SORT_OPTIONS: { label: string; value: SortOption }[] = [
  { label: 'Default', value: '' },
  { label: 'Title A-Z', value: 'dc:title' },
  { label: 'Title Z-A', value: '-dc:title' },
  { label: 'Artist A-Z', value: 'dc:creator' },
  { label: 'Artist Z-A', value: '-dc:creator' },
  { label: 'Date ↑', value: 'dc:date' },
  { label: 'Date ↓', value: '-dc:date' },
];

export default function BrowsePage() {
  const navigate = useNavigate();
  const selectedServer = useAppStore((s) => s.selectedServer);
  const selectedPlayer = useAppStore((s) => s.selectedPlayer);
  const browseState = useAppStore((s) => s.browseState);
  const updateBrowseState = useAppStore((s) => s.updateBrowseState);
  const [items, setItems] = useState<BrowsableItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [showSortMenu, setShowSortMenu] = useState(false);

  const searchTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const isSearchingRef = useRef(false);
  const isFetchingRef = useRef(false);
  const latestRequestRef = useRef(0);
  const searchQueryRef = useRef('');

  const objectId = browseState.objectId;
  const breadcrumb = browseState.breadcrumb;
  const sortBy = browseState.sortBy as SortOption;

  const isSearching = searchQuery.trim().length > 0;

  useEffect(() => {
    searchQueryRef.current = searchQuery;
  }, [searchQuery]);

  const fetchItems = useCallback(
    async (oid: string, index: number, currentSort?: SortOption, query?: string) => {
      if (!selectedServer) return;
      if (index > 0 && isFetchingRef.current) return;
      const requestId = ++latestRequestRef.current;
      isFetchingRef.current = true;
      if (index === 0) setLoading(true);
      if (index > 0) setLoadingMore(true);
      const activeSort = currentSort ?? sortBy;
      try {
        let result;
        const trimmedQuery = query?.trim() ?? '';
        const searching = trimmedQuery.length > 0;
        if (searching) {
          result = await searchApi(selectedServer.id, trimmedQuery, oid, index, PAGE_SIZE, activeSort);
        } else {
          result = await browseApi(selectedServer.id, oid, index, PAGE_SIZE, activeSort);
        }
        if (requestId !== latestRequestRef.current) return;
        isSearchingRef.current = searching;
        setItems((prev) => (index === 0 ? result.items : [...prev, ...result.items]));
        setHasMore(index + result.count < result.total);
      } catch (err: any) {
        if (requestId !== latestRequestRef.current) return;
        setError(err.message || 'Failed to load content');
      } finally {
        if (requestId === latestRequestRef.current) {
          setLoading(false);
          setLoadingMore(false);
          isFetchingRef.current = false;
        }
      }
    },
    [selectedServer, sortBy]
  );

  const doBrowse = useCallback(
    (oid: string, index: number = 0, currentSort?: SortOption, newBreadcrumb?: { id: string; title: string }[]) => {
      updateBrowseState({ objectId: oid });
      if (currentSort !== undefined) {
        updateBrowseState({ sortBy: currentSort });
      }
      if (newBreadcrumb) {
        updateBrowseState({ breadcrumb: newBreadcrumb });
      }
      setLoading(index === 0);
      setError(null);
      if (index > 0) setLoadingMore(true);
      fetchItems(oid, index, currentSort, isSearchingRef.current ? searchQueryRef.current : undefined);
    },
    [fetchItems, updateBrowseState]
  );

  useEffect(() => {
    if (selectedServer) {
      doBrowse(browseState.objectId, 0, undefined, browseState.breadcrumb);
    } else {
      navigate('/servers');
    }
  }, [selectedServer, doBrowse, navigate]);

  useEffect(() => {
    if (loading || loadingMore) return;
    if (!hasMore) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !isFetchingRef.current) {
          const query = isSearchingRef.current ? searchQueryRef.current : undefined;
          fetchItems(objectId, items.length, undefined, query);
        }
      },
      { rootMargin: '200px' }
    );

    const current = sentinelRef.current;
    if (current) observer.observe(current);
    return () => {
      observer.disconnect();
    };
  }, [objectId, items.length, hasMore, loading, loadingMore, fetchItems]);

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setSearchQuery(value);

    if (searchTimeoutRef.current) {
      clearTimeout(searchTimeoutRef.current);
    }

    searchTimeoutRef.current = setTimeout(() => {
      if (value.trim()) {
        fetchItems(objectId, 0, undefined, value.trim());
      } else {
        isSearchingRef.current = false;
        fetchItems(objectId, 0);
      }
    }, SEARCH_DEBOUNCE_MS);
  };

  useEffect(() => {
    return () => {
      if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);
    };
  }, []);

  const handleNavigate = (item: BrowsableItem) => {
    if (item.isContainer) {
      updateBrowseState({ breadcrumb: [...breadcrumb, { id: item.id, title: item.title }] });
      setSearchQuery('');
      isSearchingRef.current = false;
      doBrowse(item.id, 0);
    } else {
      if (!selectedPlayer) {
        navigate('/players');
        return;
      }
      navigate('/playback', { state: { item, autoplay: true } });
    }
  };

  const handleBreadcrumbClick = (index: number) => {
    const newBreadcrumb = breadcrumb.slice(0, index + 1);
    updateBrowseState({ breadcrumb: newBreadcrumb });
    setSearchQuery('');
    isSearchingRef.current = false;
    doBrowse(newBreadcrumb[newBreadcrumb.length - 1].id, 0);
  };

  const handleBack = () => {
    if (breadcrumb.length <= 1) {
      navigate('/players');
      return;
    }
    handleBreadcrumbClick(breadcrumb.length - 2);
  };

  const handleSortChange = (value: SortOption) => {
    updateBrowseState({ sortBy: value });
    setShowSortMenu(false);
    setSearchQuery('');
    isSearchingRef.current = false;
    setItems([]);
    doBrowse(objectId, 0, value);
  };

  const currentSortLabel = SORT_OPTIONS.find((o) => o.value === sortBy)?.label ?? 'Default';

  if (!selectedServer) {
    return (
      <div className="min-h-screen bg-gray-100 flex flex-col">
        <Header title="Browse Media" showBack />
        <main className="flex-1 flex items-center justify-center mt-14">
          <LoadingSpinner />
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col">
      <Header title={selectedServer.name} showBack onBack={handleBack} showPlayer={!!selectedPlayer} />
      <main className="flex-1 overflow-y-auto px-4 py-4 mt-14">
        <nav className="flex items-center gap-1 overflow-x-auto pb-2 mb-3 scrollbar-hide">
          {breadcrumb.map((crumb, idx) => (
            <span key={crumb.id} className="flex items-center shrink-0">
              {idx > 0 && (
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  className="h-4 w-4 text-gray-400 mx-1"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 5l7 7-7 7"
                  />
                </svg>
              )}
              <button
                onClick={() => handleBreadcrumbClick(idx)}
                className="text-sm text-gray-600 hover:text-gray-900 px-2 py-1 rounded hover:bg-gray-200 transition-colors"
              >
                {crumb.title}
              </button>
            </span>
          ))}
        </nav>

        <div className="flex gap-2 mb-3">
          <div className="relative flex-1">
            <svg
              xmlns="http://www.w3.org/2000/svg"
              className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              />
            </svg>
            <input
              type="text"
              value={searchQuery}
              onChange={handleSearchChange}
              placeholder="Search items…"
              className="w-full pl-10 pr-8 py-2 bg-white rounded-lg shadow-sm text-sm border border-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-900"
            />
            {searchQuery && (
              <button
                onClick={() => {
                  setSearchQuery('');
                  isSearchingRef.current = false;
                  fetchItems(objectId, 0);
                }}
                className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
              >
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  className="h-4 w-4"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            )}
          </div>

          <div className="relative">
            <button
              onClick={() => setShowSortMenu(!showSortMenu)}
              className="flex items-center gap-1.5 px-3 py-2 bg-white rounded-lg shadow-sm text-sm border border-gray-200 hover:bg-gray-50 transition-colors"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-4 w-4 text-gray-500"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M3 4h13M3 8h9m-9 4h6m4 0l4-4m0 0l4 4m-4-4v12"
                />
              </svg>
              <span className="text-gray-700 hidden sm:inline">{currentSortLabel}</span>
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-3 w-3 text-gray-400"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 9l-7 7-7-7"
                />
              </svg>
            </button>

            {showSortMenu && (
              <>
                <div
                  className="fixed inset-0 z-10"
                  onClick={() => setShowSortMenu(false)}
                />
                <div className="absolute right-0 z-20 mt-1 w-44 bg-white rounded-lg shadow-lg border border-gray-200 py-1">
                  {SORT_OPTIONS.map((option) => (
                    <button
                      key={option.value}
                      onClick={() => handleSortChange(option.value)}
                      className={`w-full text-left px-3 py-2 text-sm hover:bg-gray-50 transition-colors ${
                        sortBy === option.value ? 'text-gray-900 font-medium bg-gray-50' : 'text-gray-600'
                      }`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>

        {loading && items.length === 0 ? (
          <LoadingSpinner />
        ) : error && items.length === 0 ? (
          <div className="text-center py-8">
            <p className="text-red-600 mb-2">{error}</p>
            <button
              onClick={() => doBrowse(objectId, 0)}
              className="px-4 py-2 bg-gray-900 text-white rounded hover:bg-gray-800"
            >
              Retry
            </button>
          </div>
        ) : items.length === 0 ? (
          <div className="text-center py-8 text-gray-600">
            {isSearching ? `No items matching "${searchQuery}"` : 'This folder is empty.'}
          </div>
        ) : (
          <>
            {isSearching && (
              <p className="text-xs text-gray-500 mb-2">
                Searching for "{searchQuery}"
              </p>
            )}
            <ul className="space-y-2">
              {items.map((item) => {
                const type = mediaType(item.mimeType);
                const dateLabel = item.isContainer ? mediaDateLabel(item) : null;
                return (
                  <li key={item.id}>
                    <button
                      onClick={() => handleNavigate(item)}
                      className="w-full text-left bg-white rounded-lg shadow-sm px-4 py-3 hover:bg-gray-50 transition-colors flex items-center gap-3 min-h-[56px]"
                    >
                      {item.isContainer ? (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-6 w-6 text-blue-500 shrink-0"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"
                          />
                        </svg>
                      ) : (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-6 w-6 text-gray-400 shrink-0"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                          />
                        </svg>
                      )}
                      <div className="flex-1 min-w-0">
                        <p className="font-medium text-gray-900 truncate">
                          {item.title}
                        </p>
                        {!item.isContainer ? (
                          <div className="flex items-center gap-2 mt-0.5">
                            <span className="text-xs px-1.5 py-0.5 bg-gray-200 text-gray-600 rounded">
                              {typeLabel(type)}
                            </span>
                            {item.duration && (
                              <span className="text-xs text-gray-400">
                                {item.duration}
                              </span>
                            )}
                          </div>
                        ) : (
                          dateLabel && (
                            <div className="flex items-center gap-2 mt-0.5">
                              <span className="text-xs text-gray-400">
                                Latest: {dateLabel}
                              </span>
                            </div>
                          )
                        )}
                      </div>
                    </button>
                  </li>
                );
              })}
            </ul>
            <div ref={sentinelRef} className="h-4" />
            {(loading || loadingMore) && (
              <div className="text-center py-4">
                <LoadingSpinner />
              </div>
            )}
          </>
        )}
      </main>
    </div>
  );
}

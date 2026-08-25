# Step 27 — Actually show the thumbnails

**Phase:** 4 — Features and cleanup
**Severity:** Medium (report: M18)
**Files:** `frontend/src/pages/BrowsePage.tsx`, `frontend/src/pages/PlaybackPage.tsx`
**Depends on:** step-17 (thumbnail endpoint content type), step-26 (`getThumbnail` fix)

## Problem

The backend does real work to make thumbnails available:

- `parseItem` picks the `albumart` `<res>` element out of every DIDL-Lite item,
- `ThumbnailService` caches the URL per (server, item),
- `GET /api/servers/{id}/thumbnail/{itemId}` proxies and content-type-sniffs the image,
- `BrowsableItem.thumbnailUrl` is serialised to the client.

None of it is displayed. `BrowsePage` draws a generic SVG file icon for every item, and
`PlaybackPage` has:

```tsx
const [thumbnailUrl] = useState('');       // never set, no setter
...
{thumbnailUrl && <img src={thumbnailUrl} ... />}   // dead branch
```

So a whole backend feature is built, tested, and invisible.

## Change

### 1. `BrowsePage` — render the thumbnail when the item has one

Import the helper at the top:

```tsx
import { getThumbnail } from '@/api/browse';
```

Replace the media-item icon branch (the `<svg>` with the document path) so it prefers a
thumbnail and falls back to the icon:

```tsx
                      {item.isContainer ? (
                        /* folder svg — unchanged */
                      ) : item.thumbnailUrl && selectedServer ? (
                        <img
                          src={getThumbnail(selectedServer.id, item.id)}
                          alt=""
                          loading="lazy"
                          className="h-10 w-10 rounded object-cover shrink-0 bg-gray-200"
                          onError={(e) => {
                            // The proxy 404s when the URL has fallen out of the backend cache.
                            // Hide the broken image rather than showing a browser placeholder.
                            e.currentTarget.style.display = 'none';
                          }}
                        />
                      ) : (
                        /* document svg — unchanged */
                      )}
```

`loading="lazy"` matters: a 50-item page would otherwise issue 50 proxy requests at once,
each of which is a fetch from the DLNA server.

### 2. `PlaybackPage` — drive the poster from the active item

Delete the dead state:

```tsx
const [thumbnailUrl] = useState('');
```

Derive it from `item` instead, next to the other `useMemo`s:

```tsx
  const thumbnailUrl = useMemo(() => {
    if (!item?.thumbnailUrl || !selectedServer) return '';
    return getThumbnail(selectedServer.id, item.id);
  }, [item, selectedServer]);
```

Add the imports:

```tsx
import { getThumbnail } from '@/api/browse';
import { useAppStore } from '@/store/useAppStore';   // already imported
```

and read the server from the store alongside `selectedPlayer`:

```tsx
  const selectedServer = useAppStore((s) => s.selectedServer);
```

The existing `{thumbnailUrl && <img ... />}` block now has a real value and needs only an
error handler added:

```tsx
              <img
                src={thumbnailUrl}
                alt=""
                className="w-32 h-32 object-cover rounded-lg mx-auto mb-4"
                onError={(e) => { e.currentTarget.style.display = 'none'; }}
              />
```

Note this is the *DLNA server's* thumbnail, shown while TMDB artwork loads (or when TMDB
is not configured). `TmdbMediaPanel` renders its own poster separately — the two do not
conflict.

## Do not

- Do not remove the SVG fallbacks — most DLNA items have no `albumart` resource at all.
- Do not preload or prefetch thumbnails; lazy loading is deliberate.
- Do not add a spinner per thumbnail. A grey `bg-gray-200` box while the image loads is
  enough and avoids 50 spinners on a page.

## Verify

```bash
cd frontend && npm run typecheck && npm run build
```

Then, in the app: browse a folder that has cover art (music albums are the usual case) and
confirm thumbnails render. Open the Network tab and confirm images load lazily as you
scroll, not all at once. Open an item with no thumbnail and confirm the file icon still
shows.

# DLNA Hub - Agent Reference

## Quick Start
- Backend runs on port **9100**, Frontend dev server on **5173**
- Vite proxies `/api` -> `http://localhost:9100`
- `@` path alias resolves to `frontend/src/`

## Commands
```
cd frontend && npm run dev          # Start frontend dev server
cd frontend && npm run build        # Production build
cd frontend && npm run typecheck    # TypeScript check
cd backend && mvn spring-boot:run   # Start backend
```

## Stack
- **Backend**: Java 21, Spring Boot 3.3.5, jupnp 3.0.2 (DLNA/UPnP), Lombok
- **Frontend**: Vite 6, React 18, TypeScript, TailwindCSS 3, Zustand, Axios, React Router 6
- **Package**: `com.dlnahub`

## Testing environment
In my network I have a Synology NAS as the DLNA server and
 an XBox One as the renderer.

## jUPnP docs
For working with jUPnP, please use documentation at

https://www.jupnp.org/docs/basic-api
https://www.jupnp.org/docs/creating-services
https://www.jupnp.org/docs/advanced

...and the JavaDoc at https://www.javadoc.io/doc/org.jupnp/org.jupnp
Don't guess how it's used and don't try decompiling the code - work from the docs or the clone of the repo in ./jupnp

## API Endpoints (all under `/api`)

### Servers (`ServerController`, `BrowseController`)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/servers` | List discovered DLNA servers |
| POST | `/servers/{id}/subscribe` | Subscribe to server events |
| DELETE | `/servers/{id}/unsubscribe` | Unsubscribe |
| GET | `/servers/{id}/browse` | Browse content (params: `objectId`, `index`, `count`, `filter`, `sortBy`) |
| GET | `/servers/{id}/browse/{itemId}/metadata` | Get item metadata |
| GET | `/servers/{id}/thumbnail/{itemId}` | Thumbnail proxy (image/jpeg) |

### Players (`PlayerController`, `PlaybackController`)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/players` | List discovered DLNA renderers |
| GET | `/players/{id}` | Get single player |
| POST | `/players/{id}/play` | Play media (body: `PlayRequestDto` with uri, title, artist, etc.) |
| POST | `/players/{id}/pause` | Pause playback |
| POST | `/players/{id}/stop` | Stop playback |
| POST | `/players/{id}/seek` | Seek (body: `{ seconds: number }`) |
| POST | `/players/{id}/forward` | Skip forward 10s |
| POST | `/players/{id}/backward` | Skip backward 10s |
| GET | `/players/{id}/status` | Playback status (`PlaybackStatusDto`) |
| GET | `/players/{id}/volume` | Get volume |
| PUT | `/players/{id}/volume` | Set volume (body: `{ volume: number }`) |

## Frontend Architecture

### Routing (`App.tsx`)
```
/         -> HomePage
/servers  -> ServerSelectPage
/players  -> PlayerSelectPage
/browse   -> BrowsePage
/playback -> PlaybackPage
```

### State Management (Zustand)
- **`useAppStore`** (`store/useAppStore.ts`): `selectedServer`, `selectedPlayer`, `servers[]`, `players[]`
- **`usePlaybackStore`** (`store/usePlaybackStore.ts`): `status`, `isPlaying`, `currentTime`, `duration`, `volume`

### API Layer (`api/`)
- `api/axios.ts` - Axios instance (baseURL: `VITE_API_URL` or `/api`, 10s timeout)
- `api/servers.ts` - `getServers()`, `subscribeToServer()`, `unsubscribeFromServer()`
- `api/players.ts` - `getPlayers()`, `getPlayer()`
- `api/browse.ts` - `browse()`, `getMetadata()`, `getThumbnail()`
- `api/playback.ts` - `play()`, `pause()`, `stop()`, `seek()`, `forward()`, `backward()`, `getStatus()`, `getVolume()`, `setVolume()`

### Types (`types/`)
- `server.ts` - `MediaServer`
- `player.ts` - `Renderer`
- `media.ts` - `BrowsableItem`, `BrowseResult`
- `playback.ts` - `PlaybackStatus`

### Utilities (`utils/`)
- `formatTime.ts` - `formatTime(seconds)`, `parseTime(timeStr)` - HH:MM:SS / M:SS format
- `apiUrls.ts` - URL builder helpers

### Components (`components/`)
- `ErrorBoundary.tsx` - Top-level error boundary
- `Header.tsx` - Fixed top header with optional back button
- `Layout.tsx` - Basic page layout wrapper
- `LoadingSpinner.tsx` - Spinner component

### Pages (`pages/`)
- `HomePage.tsx` - Landing page, CTA to `/servers`
- `ServerSelectPage.tsx` - Polls servers every 10s, cards with name/manufacturer/model, navigates to `/players`
- `PlayerSelectPage.tsx` - Polls players every 10s, validates server selected (redirects to `/servers`), navigates to `/browse`
- `BrowsePage.tsx` - Breadcrumb nav, folder/media listing, pagination, navigates to `/playback` on media tap
- `PlaybackPage.tsx` - Status polling (1s), scrubber, play/pause/stop/forward/backward controls, volume slider

## Styling Conventions
- Mobile-first, TailwindCSS utility classes
- Background: `bg-gray-100`, cards: `bg-white rounded-lg shadow-sm`
- Dark header: `bg-gray-900 text-white`
- Touch targets: min 48x48px, cards min 60px height
- Header is fixed `top-0`, pages add `mt-14` to main content

## Backend Key Classes
- `DiscoveryManager` - DLNA server discovery
- `RendererDiscoveryManager` - DLNA player/renderer discovery
- `ContentBrowseService` - Server content browsing (ContentDirectory)
- `AvTransportService` - Playback control (AVTransport)
- `RenderingControlService` - Volume control (RenderingControl)
- `UpnpServiceManager` - jUpnp lifecycle management

## Stages
- Documented in `PLAN.md` and `stage-N.md` files
- Complete sequentially (1-8)
- **Stage 7 complete**: All pages implemented with real API integration
- **Next**: Stage 8 (Docker build & compose)

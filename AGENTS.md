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

## Test NAS facts (Synology DS918+) — established, do not re-verify

- **The NAS exposes no date data for folders.** This is why date ordering uses the
  client-side "effective date" workaround (`enrichContainerDates` in
  `ContentBrowseService`, which computes each folder's latest descendant media date).
  The workaround is expensive (extra GetSystemUpdateID round-trip + subtree crawls), so it
  is deliberately gated to date-based sorts only (`isDateSort`, i.e. `sortBy` contains
  `dc:date`). Every other sort and the metadata endpoint use plain server calls — keep it that way.
- **`SortCriteria` is silently ignored** (no sort capabilities reported) → sorting happens
  client-side (`sortItems` / client-sort paths).
- **`Search` with criteria answers UPnP 501** → in-memory search fallback.
- The **global `GetSystemUpdateID` bumps every ~30–90 s** with no library changes → the
  effective-date cache uses a stale-while-revalidate grace (`STALE_GRACE_MS`).

## Completion notification (Discord)
When work on this repo is finished (a task, fix, or deployment is complete),
notify the owner via this Discord webhook with a short summary of what was done:

```
curl.exe -s -X POST "https://discord.com/api/webhooks/1538871903005974569/u5G5HfLSnXC78alntp7zJdyw-NUQR8v-wiw9j9gEvZi6Cvn3yLcxKsbW6zZBp2m4Snns" -H "Content-Type: application/json" -d "{\"content\":\"<short summary>\"}"
```

Discord accepts a plain `{"content": "..."}` payload. A response of `204 No Content`
(or a body containing `"id"`) means success; a `4xx` (e.g. 40010/404) means the
webhook is invalid — report it, don't retry in a loop.

**Sandboxed-agent fallback:** in the agent's sandboxed `pwsh` context, Windows
Schannel TLS can fail (curl exit 35, `SEC_E_NO_CREDENTIALS`; .NET throws
"underlying connection closed") even though the network is fine. In that case send
the same payload with Node instead (its TLS does not use Schannel):

```
node -e "fetch('https://discord.com/api/webhooks/1538871903005974569/u5G5HfLSnXC78alntp7zJdyw-NUQR8v-wiw9j9gEvZi6Cvn3yLcxKsbW6zZBp2m4Snns',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content:'<short summary>'})}).then(r=>console.log(r.status))"
```

(A benign libuv assertion on Node exit after a successful 204 can be ignored.)

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

## Deployment — Kubernetes (Production)

Docker and kubectl are available on this machine.

The solution runs as a single pod with 2 containers in the `dlna-hub` namespace on a K3s cluster.

- **`hostNetwork: true`** — Required for DLNA/SSDP UDP multicast to work
- **`dnsPolicy: ClusterFirstWithHostNet`** — Retains cluster DNS despite host network
- **Backend** (`rdomloge/dlna-hub-backend:latest`): Spring Boot on **port 9200** (overridden via `SERVER_PORT=9200`, default 9100)
- **Frontend** (`rdomloge/dlna-hub-frontend:latest`): Nginx on **port 9201**, proxies `/api` → `127.0.0.1:9200`
- **LoadBalancer service**: port **9090** → frontend 9201, accessible on any K3s node IP
- **Secrets**: `dlna-hub-secret` with `TMDB_API_KEY` and `TMDB_API_READ_ACCESS_TOKEN`

### kubeconfig

kubectl requires an explicit `--kubeconfig` flag on this machine:

```bash
kubectl --kubeconfig 'C:\Users\Ramsay Domloge\k3s.yaml'
```

### Before building — test locally first

**ALWAYS test changes locally against the Synology NAS before building Docker images or pushing to the registry.**

1. Start the backend: `cd backend && mvn spring-boot:run`
2. Verify the fix works: send HTTP requests to `http://localhost:9100/api/servers/<id>/browse?sortBy=...` and check results
3. Run unit tests: `cd backend && mvn test`
4. Only after local verification passes — build, push, and deploy

This prevents pushing broken images to the cluster.

### Building and pushing images

```bash
cd C:\repos\dlna-hub
docker buildx build --platform linux/amd64,linux/arm64 -f backend/Dockerfile -t rdomloge/dlna-hub-backend:latest --push .
docker buildx build --platform linux/amd64,linux/arm64 -f frontend/Dockerfile -t rdomloge/dlna-hub-frontend:latest --push .
kubectl --kubeconfig 'C:\Users\Ramsay Domloge\k3s.yaml' rollout restart deployment/dlna-hub -n dlna-hub
```

### K8s manifests

| File | Purpose |
|------|---------|
| `k8s/deployment.yml` | Deployment — 2 containers (backend + frontend), probes, resource limits |
| `k8s/service.yml` | LoadBalancer — port 9090 → frontend 9201 |
| `k8s/secret.yml` | TMDB credentials |

## Stages
- Documented in `PLAN.md` and `stage-N.md` files
- Complete sequentially (1-8)
- **Stage 7 complete**: All pages implemented with real API integration
- **Stage 8 complete**: Kubernetes deployment (backend + frontend images on Docker Hub)
- **Production port**: 9200 (backend), 9201 (frontend), 9090 (external LoadBalancer)

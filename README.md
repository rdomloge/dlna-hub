# DLNA Hub

A DLNA/UPnP media browser and player controller for rendering devices (Xbox, Smart TVs, etc.) with TMDB metadata enrichment.

## Intended Use & Security Posture

This project is intended to run on a **closed home network** for home media streaming between your own devices. It has **no authentication by design**, and security is a secondary concern: the threat model is LAN-only, so cross-origin access to the backend from public websites is not a concern.

Controls like the CORS restriction to the Vite dev origin (`cors.allowed-origins`) are a cheap baseline, not a core security control — do not expect (or add) web-facing hardening such as auth, CSRF protection, or rate limiting unless it is explicitly asked for.

## Testing environment

The local network contains real UPnP devices to test against (discovered automatically via SSDP multicast):

- **Synology DS918+** — DLNA media server. Two implementation quirks to be aware of:
  - Its ContentDirectory **silently ignores `SortCriteria`** (`GetSortCapabilities` returns empty and item order never changes regardless of the requested sort). The backend therefore performs in-memory sorting when a sort is requested (see `ContentBrowseService.sortItems`).
  - It advertises a `Search` action but answers it with a UPnP error. The backend falls back to in-memory search when the action fails.
- **Xbox One** — DLNA/DIAL media renderer for playback tests.

## Configuration

All settings have environment-variable overrides. Defaults are safe on any network.

| Variable | Default | Purpose |
|----------|---------|---------|
| `SERVER_PORT` | `9100` | HTTP port (production sets `9200`) |
| `NETWORK_INTERFACE` | *(auto)* | Interface name or comma-separated local IPs to bind SSDP to. Leave unset unless discovery picks the wrong interface. |
| `DISCOVERY_INTERVAL` | `60000` | Milliseconds between SSDP searches |
| `REMOTE_DEVICE_MAX_AGE_SECONDS` | `90` | How long a device stays listed after its last heartbeat. Keep above `DISCOVERY_INTERVAL / 1000`. |
| `STATIC_DEVICE_CHECK_INTERVAL` | `30000` | Milliseconds between static-device checks. Does nothing unless `dlna.static-devices` is configured. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Origins allowed to call the API cross-origin. Never set to `*`. |
| `TMDB_API_READ_ACCESS_TOKEN` | *(unset)* | Enables TMDB metadata. Without it `/api/tmdb/search` returns `{"available": false}`. |

### Static devices (optional)

Some devices are hidden from SSDP by Windows AppContainer isolation. To reach one anyway,
configure it explicitly in `application.yml` or via environment variables. If the descriptor
URL is unreachable, the backend scans the configured port range on that host to find it —
so set a narrow range.

## Deployment — Kubernetes

The solution runs as a single ReplicaSet (1 replica) in the `dlna-hub` namespace on a K3s cluster.

### Architecture

```
┌─────────────────────────────────────────────┐
│  K3s Node (10.0.0.x)                       │
│                                             │
│  Pod: dlna-hub (hostNetwork: true)          │
│  ┌──────────────┐  ┌─────────────────────┐  │
│  │  backend     │  │  frontend           │  │
│  │  Spring Boot │  │  Nginx              │  │
│  │  :9200       │  │  :9201              │  │
│  │              │  │                     │  │
│  │  • DLNA/UPnP │  │  • Serves SPA       │  │
│  │  • REST API  │  │  • /api → 127.0.0.1 │  │
│  │  • jUPnP     │  │    :9200            │  │
│  └──────────────┘  └─────────────────────┘  │
│       ↕                              ↕       │
│   Multicast SSDP    Static files +           │
│   DLNA protocol     API proxy               │
└─────────────────────────────────────────────┘
         ↕
    ┌──────────┐
    │ LoadBalancer │
    │ Port 9090  │──→ Frontend :9201
    └──────────┘
```

### Networking

- **`hostNetwork: true`** — Both containers share the node's network namespace. This is **required** because DLNA/SSDP uses UDP multicast which doesn't work through Kubernetes Service/overlay networking.
- **`dnsPolicy: ClusterFirstWithHostNet`** — Retains cluster DNS resolution despite host network.
- **Backend port 9200** — Exposed directly on the node. The backend binds to `SERVER_PORT=9200` (overrides default 9100 in `application.yml`).
- **Frontend port 9201** — Nginx serves the React SPA on port 9201 and proxies `/api/*` requests to the backend at `127.0.0.1:9200`.
- **LoadBalancer service** (port 9090 → target 9201) — K3s assigns multiple node IPs (10.0.0.9, 10.0.0.10, etc.) to the LoadBalancer. The frontend is accessible on any node IP at `http://<node-ip>:9090`.

### Resources

| Container | CPU Request | CPU Limit | Memory Request | Memory Limit |
|-----------|------------|-----------|----------------|--------------|
| backend   | 100m       | 3000m (3 cores) | 128Mi    | 640Mi        |
| frontend  | 10m        | 200m      | 32Mi           | 128Mi        |

The backend gets a high CPU limit (3 cores) because jUPnP device scanning is CPU-intensive.

### Probes

| Container | Startup | Liveness | Readiness |
|-----------|---------|----------|-----------|
| backend   | TCP 9200 (5s × 30, 10s delay) | HTTP /actuator/health/liveness:9200 (30s) | HTTP /actuator/health/readiness:9200 (10s) |
| frontend  | — | HTTP /:9201 (30s, 5s delay) | HTTP /:9201 (10s, 3s delay) |

### Secrets

A Kubernetes Secret (`dlna-hub-secret`) provides TMDB API credentials injected via `envFrom`:

| Key | Purpose |
|-----|---------|
| `TMDB_API_READ_ACCESS_TOKEN` | TMDB JWT read access token. The only credential the backend reads. |

Credentials live only in `k8s/secret.yml`, which is gitignored. `k8s/secret.example.yml`
is the committed template.

### Ports Summary

| Port | Service | Container | Access |
|------|---------|-----------|--------|
| 9200 | Backend (Spring Boot + Actuator) | backend | Host network — reachable from any container on the node |
| 9201 | Frontend (Nginx) | frontend | Host network — reachable from any container on the node |
| 9090 | LoadBalancer service | → frontend:9201 | External — any node IP |

### Applying the Deployment

```bash
# Create the namespace
kubectl create namespace dlna-hub

# Create the secret from the template. k8s/secret.yml is gitignored --
# never commit the filled-in copy.
cp k8s/secret.example.yml k8s/secret.yml
$EDITOR k8s/secret.yml          # replace REPLACE_ME with your TMDB token
kubectl apply -f k8s/secret.yml -n dlna-hub

# Apply the deployment and service
kubectl apply -f k8s/deployment.yml -n dlna-hub
kubectl apply -f k8s/service.yml -n dlna-hub
```

### Rebuilding and Pushing Images

```bash
# Login to Docker Hub
docker login

# Build and push backend
docker buildx build --platform linux/amd64,linux/arm64 -f backend/Dockerfile -t rdomloge/dlna-hub-backend:latest --push .

# Build and push frontend
docker buildx build --platform linux/amd64,linux/arm64 -f frontend/Dockerfile -t rdomloge/dlna-hub-frontend:latest --push .

# Update the deployment (trigger image pull)
kubectl rollout restart deployment/dlna-hub -n dlna-hub
```

### K8s Manifests

| File | Purpose |
|------|---------|
| `k8s/deployment.yml` | Deployment with 2 containers (backend + frontend) |
| `k8s/service.yml` | LoadBalancer service (port 9090 → frontend 9201) |
| `k8s/secret.example.yml` | Template for the TMDB credentials. Copy to `k8s/secret.yml` (gitignored) and fill in before deploying. |

## Business Rules

### DLNA Device Discovery

- **Media servers** (UPnP `MediaServer` devices) are discovered automatically via SSDP multicast using jUPnP. Each server is identified by a UUID derived from its UDN via `UUID.nameUUIDFromBytes(udn)`.
- **Media renderers** (UPnP `MediaRenderer` devices) are discovered independently. Each renderer has its IP/port extracted from its descriptor URL, its supported protocols parsed from the `ConnectionManager.ProtocolInfo` state variable, and its transport capabilities extracted from its `AVTransport` actions.
- Both discovery managers register/unregister devices as `deviceAdded` and `deviceRemoved` callbacks fire from jUPnP's registry. Embedded devices are recursed into.
- The effective-date cache is invalidated by **polling** `GetSystemUpdateID`, not by GENA events. Subscribing to the ContentDirectory's `ContainerUpdateIDs` event would let the cache invalidate precisely instead, and is worth doing if enrichment cost becomes a problem — it is not implemented today.

### Content Browsing

- Browse requests navigate a DLNA content hierarchy rooted at object ID `"0"`. The `BrowseDirectChildren` flag is used for all navigation.
- The default filter requests: `dc:title,upnp:class,dc:date,dc:creator,res,res@duration,res@resolution,res@size,dc:description,upnp:artist,upnp:album,upnp:genre,dlna:profileID,refID,protocolInfo`.
- Items are parsed from the DIDL-Lite XML response. A `BrowsableItem` carries: id, parentId, title, artist, album, duration, resolution, mimeType, size, protocolInfo, isContainer, thumbnailUrl, classType, description, date, effectiveDate, and resourceName.
- Containers are identified by `classType` starting with `object.container` (e.g. `object.container.folder`).
- Thumbnails are cached per (serverId, itemId) in a `ConcurrentHashMap` as soon as they appear in browse results, so the thumbnail proxy endpoint can serve them without re-fetching from the DLNA server.

### Client-Side Sorting

- Some DLNA servers (notably Synology) silently ignore `SortCriteria` — `GetSortCapabilities` returns an empty list and item order never changes. The backend probes each server once via a raw `GetSortCapabilities` SOAP call and caches the result in `serverSortSupport`.
- When a server does not support sorting or the client explicitly requests sorting:
  - The entire container is fetched in pages of 500 items (up to a hard cap of 50 000 items).
  - Items are sorted in memory by the requested criterion (`dc:title`, `dc:creator`, `dc:date`, or their negated variants like `-dc:title`).
  - Containers are placed before items when sorting by title (`dc:title`).
  - String comparisons are case-insensitive; null/missing values sort last regardless of direction.
  - Date sorting uses parsed `OffsetDateTime`/`LocalDateTime`/`LocalDate` values (naive dates treated as UTC).
  - Results are paged locally after sorting.
- Date sort triggers **container effective-date enrichment** (see below); other sorts use the server's order directly.

### Container Effective-Date Enrichment

- Some DLNA servers (the test Synology) do not expose `dc:date` for folders. A plain `dc:date` sort would push every folder to the bottom.
- **Workaround**: each container's "effective date" is computed as the latest `dc:date` among all its descendant media files, by recursively crawling the subtree via `BrowseDirectChildren`.
- Enrichment is **only** performed when `isDateSort(sortBy)` is true (i.e. `sortBy` contains `dc:date`). Every other sort pays zero enrichment cost.
- Each enrichment call costs one `GetSystemUpdateID` round-trip plus possible subtree crawls. It is intentionally gated to date-based sorts only.
- Results are cached per (server, container) keyed by the ContentDirectory's global `SystemUpdateID`. Cache entries are invalidated when the update ID changes.
- A **stale-while-revalidate** grace period (`STALE_GRACE_MS = 2 min`) keeps entries usable after the update ID changes, allowing a background re-crawl to replace them.
- Budgets limit enrichment per call: `ENRICH_MAX_ITEMS_PER_CALL = 2 000` visited items, `ENRICH_MAX_CRAWLS_PER_CALL = 300` top-level subtrees. Partial results are not cached so stale dates keep serving until a later request completes the subtree.
- Overall crawl limits: `CRAWL_MAX_TOTAL_ITEMS = 50 000` per subtree, `CRAWL_MAX_DEPTH = 50`, page size 500. Cache is cleared when it exceeds `CACHE_MAX_ENTRIES = 10 000`.

### Search

- Searches are performed via the ContentDirectory `Search` action when available. Search criteria match `dc:title`, `dc:creator`, or `upnp:album` containing the query string.
- If the `Search` action is unavailable or returns an error (e.g. Synology answers UPnP 501), the backend falls back to an **in-memory search**: it walks the container subtree breadth-first (paginated, 500 per page), bounded to 20 000 visited items and 10 folder levels, filters the results client-side by title/artist/album, then applies sorting. Results beyond those bounds are not searched, and a warning is logged.
- Both paths support date enrichment and client-side sorting identically to browsing.

### Playback Control

- Playback commands target the `AVTransport` service of a selected renderer: `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`, `GetPositionInfo`.
- When playing a media item, a DIDL-Lite metadata XML is generated (via `DidlUtils.generateSimpleMetadataXml`) with the item's URI, title, class type (`object.item.videoItem`, `object.item.audioItem.musicTrack`, or `object.item.imageItem`), and protocol info.
- **Forward** skips +10 seconds; **Backward** skips −10 seconds (clamped at 0). Both first read the current position via `GetPositionInfo`.
- Volume is controlled via the `RenderingControl` service (`GetVolume`/`SetVolume` on the `Master` channel, range 0–100).
- The frontend polls playback status every **1 second** while playing/pending, and every **5 seconds** when paused. Playback commands are idempotent; the frontend tracks `playingPending` state with a 15-second timeout.

### Playback Status

- The status endpoint returns: transport state (`PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`, etc.), track URI, track duration, track position (all in `HH:MM:SS` format), track title (extracted from metadata XML), and volume.
- If `GetPositionInfo` or `GetVolume` fails, sensible defaults are used (`00:00:00`, `null`, `null`) rather than failing the entire response.

### Thumbnail Proxy

- Thumbnails are proxied through the backend (`/api/servers/{serverId}/thumbnail/{itemId}`) because the frontend cannot always reach DLNA server URLs directly (especially in Kubernetes).
- The proxy fetches the image from the cached URL, detects the content type from magic bytes (JPEG: `FFD8FF`, PNG: `89 50 4E 47`), and serves it with a `Cache-Control: max-age=3600` header.

### TMDB Metadata Enrichment

- When a media item is playing, the frontend parses its title to extract year, season, and episode numbers using a sophisticated title-cleaning algorithm (see below).
- It then queries TMDB (`/api/tmdb/search?title=...&year=...&tv=...`) to find matching movies or TV shows.
- The backend searches TMDB's movie and TV databases, scores results by year match (+500) and type hint (+200), and returns up to 3 candidates.
- For movies, full details (overview, tagline, poster/backdrop URLs, genres, runtime, credits) are fetched. For TV shows, season info and first-season credits are fetched.
- The frontend displays a media panel with poster, backdrop, cast (up to 6), crew (directors/writers), genres, and runtime. Multiple matches let the user switch between them.
- TMDB integration is optional: if `TMDB_API_READ_ACCESS_TOKEN` is not configured, the endpoint returns `{"available": false}`.

### Media Title Parsing

The frontend's `cleanMediaTitle` utility extracts structured metadata from raw file names:

- Strips common media extensions (`.mkv`, `.mp4`, `.avi`, `.webm`, etc.).
- Removes trailing bracket groups (e.g. `[1080p]`, `[BluRay]`).
- Normalizes years in parentheses/brackets.
- Detects season/episode patterns: `S01E05`, `1x05`, `S01 E05`.
- Detects year tokens: 4-digit numbers starting with `19` or `20`.
- Strips technical suffixes: resolution tags (`720p`, `1080p`, `4k`), codec tags (`x264`, `HEVC`), release group tags (`BluRay`, `WEBRip`), audio tags (`AC3`, `DTS`), HDR tags (`HDR`, `HDR10`), and edition tags (`Director's Cut`, `Extended`).
- A "strong" technical token (resolution, format, codec, or HDR) is required for suffix stripping; otherwise at least 2 technical tokens must be present.
- Release groups are identified by all-caps 2-20 character tokens (e.g. `RAiSERiGHT`).

### UI Navigation Flow

1. **Server Select** (`/servers`) → polls `/api/servers` every 10 seconds, shows cards with name/manufacturer/model. Selecting a server navigates to `/players`.
2. **Player Select** (`/players`) → polls `/api/players` every 10 seconds, validates a server is selected (redirects to `/servers` if not). Selecting a player resets playback state and navigates to `/browse`.
3. **Browse** (`/browse`) → shows breadcrumb navigation, folder/media listing with sort/search controls. Containers navigate deeper; media items navigate to `/playback`. Pagination uses an intersection observer (lazy load). Search debounces at 400 ms.
4. **Playback** (`/playback`) → polls player status, shows scrubber, playback controls (play/pause/stop/forward/backward), volume slider, and TMDB metadata panel. Auto-plays when arriving from browse with `autoplay: true`.

### State Management

- **App store** (Zustand, persisted to localStorage): `selectedServer`, `selectedPlayer`, `servers[]`, `players[]`, `browseState` (current `objectId`, `breadcrumb`, `sortBy`). Browse state resets when a different server is selected.
- **Playback store** (Zustand, persisted to localStorage): `status`, `isPlaying`, `playingPending`, `currentTime`, `duration`, `volume`, `reconnecting`, `activeItem`. Playback state survives page reloads via `partialize` (only `isPlaying`, `currentTime`, `duration`, `volume`, `activeItem` are persisted).
- The frontend stores `VITE_API_URL` (defaults to `/api` which Vite proxies to `http://localhost:9100` in dev mode). Axios has a 60-second timeout.

### Error Handling

- DLNA errors that originate from jUPnP action failures carry an `upnpErrorCode` and are handled by `GlobalExceptionHandler` → 502 (Bad Gateway) for known UPnP errors, 500 otherwise.
- Invalid server/player IDs return 404.
- Browse/search failures propagate as exceptions: UPnP action failures return 502 with the `upnpErrorCode`, other unexpected errors return 500 with an error message.
- The frontend shows inline error states with retry buttons on discovery and browse pages. On playback, after 3 consecutive status poll errors, the user is redirected to `/players`.
- A "reconnecting" overlay appears while the playback status poll reconnects after a transient failure.

### API Endpoints (all under `/api`)

#### Servers (`ServerController`, `BrowseController`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/servers` | List discovered DLNA servers |
| GET | `/servers/{id}/browse` | Browse content (params: `objectId`, `index`, `count`, `filter`, `sortBy`) |
| GET | `/servers/{id}/search` | Search content (params: `containerId`, `query`, `index`, `count`, `filter`, `sortBy`) |
| GET | `/servers/{id}/browse/{itemId}/metadata` | Get item metadata |
| GET | `/servers/{id}/thumbnail/{itemId}` | Thumbnail proxy (image/jpeg) |

#### Players (`PlayerController`, `PlaybackController`)

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

#### TMDB (`TmdbController`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/tmdb/search` | Search TMDB (params: `title`, `year?`, `tv?`) |

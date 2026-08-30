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
cd frontend && npm run lint         # ESLint
cd frontend && npm test             # Vitest
cd backend && mvn verify            # Compile + tests + JaCoCo report
```

### Maven (agents)

`mvn test` (and `mvn clean test`, `mvn spring-boot:run`) works **out of the box**
in the sandbox — no special settings, no workspace-local `.m2`, no escalation.
Maven's resolver writes to `~/.m2/repository` are not blocked by the file
sandbox, so agents can run Maven commands directly from `backend/` without
any configuration:

```powershell
cd backend
mvn clean test           # Runs fine — 58 tests, all pass
mvn spring-boot:run      # Starts on port 9100
```

### Git (agents) — sandboxed push limitation

`git status`, `git log`, `git diff`, `git add`, `git commit` all work fine
in the sandbox. The sandbox only blocks commands that **spawn child processes**
via `CreateFileMapping` — this includes `ssh.exe`, `sh.exe`, `bash.exe`, and
any Git operation that needs to fork a subprocess.

**`git push`, `git pull`, `git fetch` over SSH fail** with "CreateFileMapping
Win32 error 5" because Git for Windows spawns `ssh.exe` as a child process.
**`git push` over HTTPS also fails** in the sandbox — Schannel TLS
(`SEC_E_NO_CREDENTIALS`) is unreliable in this environment.

**Solution: use `gh api` for all remote operations.**
`gh` uses its own HTTP-based protocol (no child process spawning):

```powershell
# Create/update branch ref via API (pushes the commit):
gh api repos/rdomloge/dlna-hub/git/refs/heads/<branch> \
  --method PATCH \
  --field sha=<commit-sha>

# Create a PR:
gh pr create --base main --head <branch> --title "..." --body-file .pr-body.md

# Verify remote state:
gh api repos/rdomloge/dlna-hub/branches/<branch> --jq '.commit.sha'
```

The `gh` CLI itself is fully sandbox-compatible — authentication uses
environment variables (`GITHUB_TOKEN`). Never waste time debugging git
remote configuration (SSH keys, TLS backends, credential helpers) when
`gh api` works directly.

### Docker buildx (agents)

`docker buildx` commands require `danger-full-access` sandbox escalation because
they need access to the Docker daemon socket (`docker.sock`) and buildx lock files
under `~/.docker`. The sandbox blocks these file operations by default.

## Stack
- **Backend**: Java 21, Spring Boot 3.3.5, jupnp 3.0.2 (DLNA/UPnP)
- **Frontend**: Vite 6, React 18, TypeScript, TailwindCSS 3, Zustand, Axios, React Router 6
- **Package**: `com.dlnahub`

## Testing environment
In my network I have 2 DLNA devices: a Synology NAS (the DLNA server) and
an XBox One (the renderer). Only the Synology can be expected to be on all
the time. The XBox is on only sporadically — if it is not discovered, that is
NOT a failure mode and must not be waited for: assume it is currently off,
and either verify against the NAS or skip renderer-specific live checks.
The owner can turn the renderer on when a live test would benefit from it,
but it must never be assumed to be on: either assume it is off, or ask the
owner — "Can you turn on the test renderer?" — and wait for the answer
before continuing. The answer will be "I have turned it on, please
continue" or "I am not going to turn it on, please find a way to work
without it"; in the latter case, verify by other means (unit tests, source
inspection, NAS-only live checks) and report the renderer-specific live
check as skipped.

## House Java unit testing style
- Test classes should be named after the class they are testing, with a 'Test' suffix. (`EventDispatcherTest` tests `EventDispatcher`)
- Test methods should be named [method under test]_[scenario]_[expected outcome] (`validateUser_nullEmail_throwsInvalidDataException`)
- Test method bodies should be split into 3 clearly demarcated blocks, with comments to show this
  - `given` - this is the block that setups up the necessary state for testing; mocks, data etc etc
  - `when` - this is the block that makes the calls to simulate the system
  - `then` - this is where we verify the end state for correctness
  - Any of the above can be empty, where necessary - just leave a blank line.

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

## Security Posture
- This app targets **closed home networks only** (home media streaming between the owner's
  own devices). It has **no authentication by design**; security is a secondary concern.
- Cross-origin access to the backend from public websites is **not a threat model** — the
  hub is LAN-only. The CORS restriction to the Vite dev origin (`CorsConfig`,
  `cors.allowed-origins`) is a cheap baseline, not a core control. Do not add web-facing
  security (auth, CSRF, rate limiting) unless explicitly requested.

## Completion notification (Discord)
When  
- work on this repo is finished (a task, fix, or deployment is complete), 
- or you are about to ask a question,
- or you are about to ask permission to escalate privileges
- or anything else that pauses work and waits for the owner
...notify the owner via Discord with a short summary of what was done. The webhook URL is
supplied the webhook_url.txt file, which is in .gitignore — set it in your shell profile. If it is unset, skip the notification.

```
curl.exe -s -X POST "$DISCORD_WEBHOOK_URL" -H "Content-Type: application/json" -d "{\"content\":\"<short summary>\"}"
```

Discord accepts a plain `{"content": "..."}` payload. A response of `204 No Content`
(or a body containing `"id"`) means success; a `4xx` (e.g. 40010/404) means the
webhook is invalid — report it, don't retry in a loop.

**Sandboxed-agent fallback:** in the agent's sandboxed `pwsh` context, Windows
Schannel TLS can fail (curl exit 35, `SEC_E_NO_CREDENTIALS`; .NET throws
"underlying connection closed") even though the network is fine. In that case send
the same payload with Node instead (its TLS does not use Schannel):

```
node -e "fetch(process.env.DISCORD_WEBHOOK_URL,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content:'<short summary>'})}).then(r=>console.log(r.status))"
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
/         -> ServerSelectPage
/servers  -> ServerSelectPage
/players  -> PlayerSelectPage
/browse   -> BrowsePage
/playback -> PlaybackPage
```

### State Management (Zustand)
- **`useAppStore`** (`store/useAppStore.ts`): `selectedServer`, `selectedPlayer`, `servers[]`, `players[]`
- **`usePlaybackStore`** (`store/usePlaybackStore.ts`): `status`, `isPlaying`, `currentTime`, `duration`, `volume`

### API Layer (`api/`)
- `api/axios.ts` - Axios instance (baseURL: `VITE_API_URL` or `/api`, 60s timeout)
- `api/servers.ts` - `getServers()`
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
- **Secrets**: `dlna-hub-secret` with `TMDB_API_READ_ACCESS_TOKEN`

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
- Documented in `plans/archive/PLAN.md` and `plans/archive/stage-N.md` files
- Complete sequentially (1-8)
- **Stage 7 complete**: All pages implemented with real API integration
- **Stage 8 complete**: Kubernetes deployment (backend + frontend images on Docker Hub)
- **Production port**: 9200 (backend), 9201 (frontend), 9090 (external LoadBalancer)

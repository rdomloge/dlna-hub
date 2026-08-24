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
| `TMDB_API_KEY` | TMDB API key. Currently unused by the code; kept for compatibility. |

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

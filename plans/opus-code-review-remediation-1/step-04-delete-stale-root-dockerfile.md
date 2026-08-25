# Step 04 — Delete the stale root Dockerfile and fix the README build commands

**Phase:** 0 — Blockers
**Severity:** Medium (report: M19, L1)
**Files:** `Dockerfile` (delete), `README.md`
**Depends on:** —

## Problem

The root `Dockerfile` builds a single combined image that copies the frontend build into
`/app/static` and runs the jar. Spring Boot is **not** configured to serve static content
from `/app/static` (it serves from the classpath), so that image would serve no UI. The
real deployment uses `backend/Dockerfile` + `frontend/Dockerfile`, as `AGENTS.md` states.

Worse, `README.md` gives two build commands that are byte-identical apart from the tag —
both build the *root* Dockerfile:

```bash
docker buildx build --platform linux/amd64,linux/arm64 -t rdomloge/dlna-hub-backend:latest --push .
docker buildx build --platform linux/amd64,linux/arm64 -t rdomloge/dlna-hub-frontend:latest --push .
```

Following the README produces two identical, broken images.

## Change

### 1. Delete the root Dockerfile

```bash
git rm Dockerfile
```

### 2. Fix the README "Rebuilding and Pushing Images" section

Replace the two build commands with the ones from `AGENTS.md`, which are correct:

```bash
docker buildx build --platform linux/amd64,linux/arm64 -f backend/Dockerfile -t rdomloge/dlna-hub-backend:latest --push .
docker buildx build --platform linux/amd64,linux/arm64 -f frontend/Dockerfile -t rdomloge/dlna-hub-frontend:latest --push .
```

Note both use `-f <path>/Dockerfile` with the **repo root** as build context — the
Dockerfiles copy `backend/` and `frontend/` respectively, so the context must be the root.

## Do not

- Do not modify `backend/Dockerfile` or `frontend/Dockerfile` — both are correct.
- Do not add a `docker-compose.yml`; the deployment target is Kubernetes.

## Verify

```bash
ls Dockerfile 2>&1              # expect "No such file or directory"
grep -c "\-f backend/Dockerfile" README.md    # expect 1
grep -c "\-f frontend/Dockerfile" README.md   # expect 1
```

# Step 03 — Track `frontend/package-lock.json` so Docker builds work

**Phase:** 0 — Blockers
**Severity:** Critical (report: C3)
**Files:** `.gitignore`, `frontend/package-lock.json`
**Depends on:** —

## Problem

`.gitignore` line 31 contains `package-lock.json`, so `frontend/package-lock.json` is
untracked. Both `frontend/Dockerfile` and the root `Dockerfile` run `npm ci`, which fails
outright without a lock file. **A clean clone cannot build the frontend image.**

`npm ci` is also what makes builds reproducible — without the lock file every build could
resolve different transitive dependency versions.

## Change

### 1. Remove the ignore rule

In `.gitignore`, under the `## Node` section, delete this line:

```
package-lock.json
```

Leave every other line in that section untouched (`frontend/node_modules/`,
`frontend/dist/`, `frontend/.vite/`, `*.tsbuildinfo`).

### 2. Commit the lock file

```bash
git add -f frontend/package-lock.json
```

## Do not

- Do not run `npm install` or `npm update` — that would change the lock file's contents.
  Commit the lock file exactly as it currently stands on disk.
- Do not add a backend lock equivalent; Maven resolves from `pom.xml`.

## Verify

```bash
git check-ignore -v frontend/package-lock.json   # expect: no output, exit code 1
git ls-files | grep -c "frontend/package-lock.json"   # expect 1
cd frontend && npm ci --dry-run                  # expect success, no lockfile error
```

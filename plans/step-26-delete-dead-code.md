# Step 26 — Delete dead code

**Phase:** 4 — Features and cleanup
**Severity:** Medium (report: M19)
**Files:** see the checklist below
**Depends on:** step-24 (removes the subscribe API functions), step-27 (do **before** 27 if
you intend to use thumbnails, since 27 re-introduces one of these call sites)

## Problem

Several files, methods and dependencies exist but are referenced by nothing. Each one costs
context for anyone reading the codebase, and a couple actively mislead (`apiUrls.ts`
hardcodes a `/api` prefix that would break a relocated API; the Lombok dependency makes
`AGENTS.md` claim a stack element the code does not use).

## Change — work the checklist

Verify each with the grep given, **then** delete.

### 1. `frontend/src/utils/apiUrls.ts` — entire file

```bash
grep -rn "apiUrls" frontend/src   # expect: only the file itself
```

Every function in it is unused, and each hardcodes `/api`, ignoring `VITE_API_URL`.

```bash
git rm frontend/src/utils/apiUrls.ts
```

### 2. `frontend/src/components/Layout.tsx` — entire file

```bash
grep -rn "from '@/components/Layout'" frontend/src   # expect: no matches
git rm frontend/src/components/Layout.tsx
```

Every page renders its own `min-h-screen` wrapper directly.

### 3. `DidlUtils.generateMetadataXml` — unused method

```bash
grep -rn "generateMetadataXml" backend/src frontend/src   # expect: only the declaration
```

Only `generateSimpleMetadataXml` is called (from `AvTransportService.setUriAndPlay`).
Delete `generateMetadataXml` and the now-unused `BrowsableItem` import from `DidlUtils`.

### 4. Unused frontend API functions

Delete these, each verified unused:

| File | Function |
|------|----------|
| `frontend/src/api/browse.ts` | `getMetadata` |
| `frontend/src/api/playback.ts` | `getVolume` |
| `frontend/src/api/players.ts` | `getPlayer` |

```bash
grep -rn "getMetadata\|getPlayer(\|getVolume" frontend/src --include=*.tsx
# expect: no matches outside the api/ directory
```

**Keep** `getThumbnail` — step-27 uses it. But fix it while you are here: it hardcodes the
prefix. Change it to respect `VITE_API_URL`:

```ts
export function getThumbnail(serverId: string, itemId: string): string {
  const base = import.meta.env.VITE_API_URL || '/api';
  return `${base}/servers/${serverId}/thumbnail/${itemId}`;
}
```

### 5. The Lombok dependency

```bash
grep -rn "lombok\|@Data\|@Getter\|@Setter\|@Slf4j\|@Builder" backend/src   # expect: no matches
```

Every model and DTO uses hand-written accessors. Remove from `backend/pom.xml`:

- the `org.projectlombok:lombok` dependency block, and
- the `<excludes>` block inside the `spring-boot-maven-plugin` configuration that exists
  solely to exclude Lombok.

The plugin configuration reduces to:

```xml
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
```

Then remove "Lombok" from the Stack line in `AGENTS.md`.

### 6. The unused `TMDB_API_KEY` configuration

```bash
grep -rn "getApiKey\|apiKey" backend/src   # expect: only TmdbConfig's own field/accessors
```

`TmdbService` authenticates exclusively with the bearer token
(`tmdbConfig.getApiReadAccessToken()`), and `TmdbConfig.isEnabled()` only checks that.
Remove the `apiKey` field and its accessors from `TmdbConfig`, and the `tmdb.api-key` line
from `application.yml`. If you kept `TMDB_API_KEY` in the secret template in step-01,
remove it there too, and from the README secrets table.

## Do not

- Do not delete `hooks/useVisibility.ts` — `PlaybackPage` uses it, and step-30 extends it
  to the discovery pages.
- Do not delete `HealthController` — the k8s probes use actuator, but `/api/health` is a
  cheap manual check and is harmless.
- Do not delete `Renderer.supportedProtocols` or `transportCapabilities` even though no UI
  reads them; they are part of the documented API and are correct after step-25.

## Verify

```bash
cd backend && mvn clean test
cd frontend && npm run typecheck && npm run build
```

Both must pass with no unresolved-import errors. Then confirm the jar still starts:

```bash
cd backend && mvn spring-boot:run
curl -s http://localhost:9100/api/health   # expect {"status":"UP"}
```

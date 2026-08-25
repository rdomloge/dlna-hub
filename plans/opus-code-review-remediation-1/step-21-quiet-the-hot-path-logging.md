# Step 21 — Quiet the hot-path logging

**Phase:** 3 — Performance
**Severity:** High (report: H8)
**Files:** `backend/src/main/java/com/dlnahub/service/AvTransportService.java`,
`backend/src/main/java/com/dlnahub/controller/PlaybackController.java`,
`backend/src/main/java/com/dlnahub/service/TmdbService.java`,
`backend/src/main/java/com/dlnahub/controller/TmdbController.java`,
`frontend/src/api/axios.ts`
**Depends on:** —

## Problem

The frontend polls `GET /players/{id}/status` **once per second** while playing. Each poll
emits, at INFO:

- `PlaybackController`: `"Status request for player {}"`
- `AvTransportService.getTransportState`: `"GetTransportInfo inputs: {}"` and
  `"GetTransportInfo outputs: {}"`
- `AvTransportService.getPositionInfo`: `"GetPositionInfo inputs: {}"` and
  `"GetPositionInfo outputs: {}"`

The last four serialise the full `ActionArgument[]` array with `Arrays.toString` on **every
call**, whether or not INFO is enabled at that point. That is roughly five INFO lines per
second, per viewer, forever — the log is unreadable and it costs real CPU inside a 3-core
limit.

`resolveArgName` also logs a `log.warn` with a full argument dump every time an argument is
missing, which for some renderers is every single call.

## Change

### 1. `AvTransportService` — delete the argument-dump logs

Remove these five lines entirely (they were debugging aids that were never taken out):

```java
        log.info("SetAVTransportURI inputs: {}", java.util.Arrays.toString(setUriAction.getInputArguments()));
        log.info("GetTransportInfo inputs: {}", java.util.Arrays.toString(action.getInputArguments()));
        log.info("GetTransportInfo outputs: {}", java.util.Arrays.toString(action.getOutputArguments()));
        log.info("GetPositionInfo inputs: {}", java.util.Arrays.toString(action.getInputArguments()));
        log.info("GetPositionInfo outputs: {}", java.util.Arrays.toString(action.getOutputArguments()));
```

Change the `resolveArgName` fallback warning to DEBUG, and guard the array serialisation so
it is not built when DEBUG is off:

```java
        if (log.isDebugEnabled()) {
            log.debug("Argument '{}' not found on action {}. Available: {}",
                    standardName, action.getName(), java.util.Arrays.toString(action.getInputArguments()));
        }
        return standardName;
```

Also demote the two `log.debug("Resolved arg ...")` calls' cost the same way, or leave them
— they are already DEBUG, so they only cost a level check.

Keep the outcome logs at INFO — they are once-per-user-action, not per-poll:
`"URI set on player {} to {}"`, `"Play command sent to player {}"`, `"Pause ..."`,
`"Stop ..."`, `"Seek to {} ({}) on player {}"`.

### 2. `PlaybackController` — demote the status log

```java
        log.debug("Status request for player {}", playerId);
```

Leave `play` / `pause` / `stop` / `seek` / `forward` / `backward` / `setVolume` at INFO —
those are discrete user actions and are genuinely useful in the log. Demote
`getVolume` to DEBUG (it is polled indirectly).

### 3. `TmdbService` and `TmdbController` — demote the per-search logs

`TmdbController.search`:

```java
        log.debug("TMDB search request for: {}, year={}, tv={}", title, year, tv);
```

`TmdbService.searchByTitle`:

```java
        log.debug("Searching TMDB for title='{}', yearHint={}, tvHint={}", title, yearHint, tvHint);
```

These fire on every playback page load; the warnings on failure stay at WARN.

### 4. Frontend — do not log every request in production

`frontend/src/api/axios.ts` currently `console.debug`s every request. Guard both
interceptors so they are stripped from production builds:

```ts
if (import.meta.env.DEV) {
  api.interceptors.request.use((config) => {
    console.debug('[API] Request:', config.method?.toUpperCase(), config.url);
    return config;
  });
}

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (import.meta.env.DEV) {
      console.error('[API] Error:', error.response?.status, error.message);
    }
    return Promise.reject(error);
  }
);
```

## Do not

- Do not raise the root log level to WARN as a shortcut — the discovery and playback INFO
  lines are useful and should stay.
- Do not remove the `logging.level` overrides already in `application.yml`; they suppress
  noisy jUPnP warnings and are separately justified in comments.

## Verify

```bash
cd backend && mvn test && mvn spring-boot:run
```

Start playback from the UI, let it run for 30 seconds, then count status-related log lines:

```bash
grep -c "GetPositionInfo inputs" backend/boot.log    # expect 0
grep -c "Status request for player" backend/boot.log # expect 0 at default level
```

The log should show one line per user action, not a per-second stream.

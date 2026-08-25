# Step 24 — Remove the dead subscribe/unsubscribe endpoints

**Phase:** 4 — Features and cleanup
**Severity:** High (report: H6)
**Files:** `backend/src/main/java/com/dlnahub/controller/ServerController.java`,
`backend/src/main/java/com/dlnahub/dlna/DiscoveryManager.java`,
`frontend/src/api/servers.ts`, `README.md`, `AGENTS.md`
**Depends on:** —

## Problem

`POST /api/servers/{id}/subscribe` and `DELETE /api/servers/{id}/unsubscribe` write to and
delete from a `subscriptions` map in `DiscoveryManager`. `isSubscribed` — the only reader —
is **never called anywhere in the codebase**:

```bash
$ grep -rn "isSubscribed" backend/src
backend/src/main/java/com/dlnahub/dlna/DiscoveryManager.java:96:    public boolean isSubscribed(...)
```

No jUPnP `SubscriptionCallback` is ever registered, and the registry listener does not
consult the flag. The endpoints are no-ops. The frontend's `subscribeToServer` /
`unsubscribeFromServer` are likewise never called.

Both README and AGENTS.md describe this as working behaviour:

> Server subscriptions (`/api/servers/{id}/subscribe`) are tracked per-server in a
> `ConcurrentHashMap`; the flag is consulted by jUPnP event listeners to decide whether to
> process `SystemUpdateID` change notifications.

That is not true, and it is actively misleading — the effective-date cache polls
`GetSystemUpdateID` precisely *because* nothing is event-driven.

## Decision

**Remove it.** Real GENA event subscription is a meaningful feature (it would let the
effective-date cache invalidate on a real `ContainerUpdateIDs` event instead of polling),
but it is a design task, not a cleanup task. Shipping a documented endpoint that does
nothing is worse than not having one. Record the idea as future work.

## Change

### 1. `ServerController` — delete both endpoints

Remove the `subscribe` and `unsubscribe` methods and the now-unused `Map` import. The
controller keeps only `getServers()`.

### 2. `DiscoveryManager` — delete the subscription machinery

Remove the field and all three methods:

```java
    private final ConcurrentHashMap<String, Boolean> subscriptions = new ConcurrentHashMap<>();
    public void subscribe(String serverId) { ... }
    public void unsubscribe(String serverId) { ... }
    public boolean isSubscribed(String serverId) { ... }
```

### 3. `frontend/src/api/servers.ts` — delete both functions

The file keeps only `getServers()`. Remove nothing else.

### 4. Update the docs

In `README.md`, delete the "Server subscriptions" bullet from **DLNA Device Discovery** and
the two `/subscribe` / `/unsubscribe` rows from the Servers endpoint table.

In `AGENTS.md`, delete the same two rows from its endpoint table.

### 5. Record the follow-up

Add to `README.md` under Business Rules → DLNA Device Discovery:

```
- The effective-date cache is invalidated by **polling** `GetSystemUpdateID`, not by GENA
  events. Subscribing to the ContentDirectory's `ContainerUpdateIDs` event would let the
  cache invalidate precisely instead, and is worth doing if enrichment cost becomes a
  problem — it is not implemented today.
```

## Do not

- Do not implement GENA subscription in this step.
- Do not remove `getSystemUpdateId` or any part of the effective-date cache — polling is
  what currently works.

## Verify

```bash
cd backend && mvn test
cd frontend && npm run typecheck && npm run build
grep -rn "isSubscribed\|subscriptions" backend/src            # expect no matches
grep -rn "subscribeToServer\|unsubscribeFromServer" frontend/src  # expect no matches
grep -c "subscribe" README.md AGENTS.md                       # expect only the follow-up note
```

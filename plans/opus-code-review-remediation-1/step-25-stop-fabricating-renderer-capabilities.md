# Step 25 — Stop fabricating renderer capabilities

**Phase:** 4 — Features and cleanup
**Severity:** Medium (report: M7, M8)
**Files:** `backend/src/main/java/com/dlnahub/dlna/RendererDiscoveryManager.java`
**Depends on:** —

## Problem

Two problems in the same file.

### 1. A loop that does nothing

```java
for (Action<RemoteService> action : service.getActions()) {
    if ("GetProtocolInfo".equals(action.getName())) {
        break;
    }
}
```

No body, no side effect. It is an unfinished thought that was left in.

### 2. Invented capabilities

```java
if (protocols.isEmpty()) {
    protocols = Arrays.asList(
        "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS PAL",
        "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3"
    );
}
```

```java
if (capabilities.isEmpty()) {
    capabilities.addAll(Arrays.asList(
        "Play", "Pause", "Stop", "Seek", "Next", "Previous",
        "SetAVTransportURI", "GetTransportInfo", "GetDeviceCapabilities"));
}
```

When a device reports nothing, the code claims it supports a specific MPEG-PS PAL profile
and a full transport action set. Both are guesses presented as facts. `Renderer.
transportCapabilities` is serialised straight to the frontend, so any UI or logic that
gates on it is reasoning about a device that may support none of it. An empty list is
truthful and actionable; a fabricated one is neither.

## Change

### 1. Delete the empty loop

Remove the whole `for` block from `extractProtocols`.

### 2. Return what the device actually reported

`extractProtocols` becomes:

```java
    /**
     * Protocols the renderer advertises via ConnectionManager's ProtocolInfo state variable.
     * Returns an empty list when the device reports none — deliberately not a guessed default:
     * this list is published to clients, and inventing capabilities the device may not have is
     * worse than admitting they are unknown.
     */
    private List<String> extractProtocols(RemoteDevice device) {
        List<String> protocols = new ArrayList<>();

        for (RemoteService service : device.findServices(new UDAServiceType("ConnectionManager"))) {
            StateVariable<RemoteService> protocolInfoVar = service.getStateVariable("ProtocolInfo");
            if (protocolInfoVar == null || protocolInfoVar.getTypeDetails() == null) {
                continue;
            }
            String defaultValue = protocolInfoVar.getTypeDetails().getDefaultValue();
            if (defaultValue == null || defaultValue.isEmpty()) {
                continue;
            }
            for (String entry : defaultValue.split(";")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    protocols.add(trimmed);
                }
            }
        }

        if (protocols.isEmpty()) {
            log.debug("Renderer {} reports no ProtocolInfo", device.getDisplayString());
        }
        return protocols;
    }
```

`extractTransportCapabilities` becomes:

```java
    /**
     * The AVTransport action names the renderer actually declares in its SCPD. Empty when the
     * device declares none — see extractProtocols for why this is not defaulted.
     */
    private Set<String> extractTransportCapabilities(RemoteDevice device) {
        Set<String> capabilities = new HashSet<>();
        for (RemoteService service : device.findServices(new UDAServiceType("AVTransport"))) {
            for (Action<RemoteService> action : service.getActions()) {
                capabilities.add(action.getName());
            }
        }
        if (capabilities.isEmpty()) {
            log.debug("Renderer {} declares no AVTransport actions", device.getDisplayString());
        }
        return capabilities;
    }
```

Remove the now-unused `java.util.Arrays` import if nothing else in the file uses it.

### 3. Fix the port fallback while here

`descriptorURL.getPort()` returns `-1` when the URL uses the scheme's default port, so
renderers get reported as `host:-1`. Replace:

```java
                    ip = descriptorURL.getHost();
                    port = descriptorURL.getPort();
```

with:

```java
                    ip = descriptorURL.getHost();
                    // getPort() is -1 for a default-port URL; fall back to the scheme default.
                    port = descriptorURL.getPort() != -1
                            ? descriptorURL.getPort()
                            : descriptorURL.getDefaultPort();
```

## Do not

- Do not remove the `try`/`catch` around `processDevice` — a malformed device descriptor
  should not abort discovery of the others.
- Do not change how playback services handle a missing action; `AvTransportService`
  already throws a clear `DlnaException` when an action is absent, which is the correct
  runtime behaviour.

## Verify

```bash
cd backend && mvn test && mvn spring-boot:run
```

```bash
curl -s http://localhost:9100/api/players | python -m json.tool
```

Confirm the Xbox reports its **real** action set (it will include `Play`, `Pause`, `Stop`,
`Seek`, `SetAVTransportURI`, `GetTransportInfo`, `GetPositionInfo`, and probably more), and
that no renderer reports the fabricated `MPEG_PS PAL` protocol. Confirm the port is a real
port number, not `-1`.

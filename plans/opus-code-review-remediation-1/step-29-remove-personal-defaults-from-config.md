# Step 29 — Remove personal environment details from the shipped defaults

**Phase:** 4 — Features and cleanup
**Severity:** Medium (report: M17)
**Files:** `backend/src/main/resources/application.yml`,
`backend/src/main/java/com/dlnahub/dlna/UpnpServiceManager.java`, `README.md`
**Depends on:** —

## Problem

`application.yml` ships one developer's network as the default for everyone:

```yaml
dlna:
  network-interface: ${NETWORK_INTERFACE:10.0.0.150}
  static-device-check-interval: ${STATIC_DEVICE_CHECK_INTERVAL:5000}
  static-devices:
    - udn: ${STATIC_RENDERER_UDN:5ae95e81-4e22-43dc-bdde-50adad44df6e}
      descriptor-url: ${STATIC_RENDERER_DESCRIPTOR_URL:http://10.0.0.150:1258/}
      port-range-start: ${STATIC_RENDERER_PORT_RANGE_START:1024}
      port-range-end: ${STATIC_RENDERER_PORT_RANGE_END:2048}
```

Consequences on any other network:

- **Binding to `10.0.0.150` fails**, so jUPnP finds no interface and discovery silently
  does nothing. Nothing tells the operator why.
- `refreshConfiguredDevices` runs **every 5 seconds** looking for a renderer UDN that will
  never appear, and when the HEAD check fails it launches a **1 024-port scan** of
  `10.0.0.150` (throttled to once a minute, 32 threads). A default install port-scans an
  address it has no business touching.

The static-device feature itself is legitimate — it works around Windows AppContainer
isolation hiding devices from SSDP — but it must be opt-in.

## Change

### 1. Make the network interface auto-detect by default

```yaml
dlna:
  # Blank = let jUPnP pick the interfaces itself, which is correct on most networks.
  # Set to an interface name (e.g. "eth0") or a comma-separated list of local IPs to pin it.
  network-interface: ${NETWORK_INTERFACE:}
```

`UpnpServiceManager.configureNetworkSelection` already returns early on a blank value,
leaving jUPnP's own selection in place — no code change needed for this part.

### 2. Ship no static devices, and slow the check down

```yaml
  # Optional fallback for devices that Windows AppContainer isolation hides from local SSDP.
  # Empty by default: with no entries the periodic check does nothing. Configure via
  # DLNA_STATIC_DEVICES_0_UDN / _DESCRIPTOR_URL when you actually need it.
  static-device-check-interval: ${STATIC_DEVICE_CHECK_INTERVAL:30000}
  static-devices: []
```

### 3. Warn when the port scan is about to happen

The descriptor port scan is surprising behaviour and should announce itself. In
`UpnpServiceManager.findConfiguredDescriptor`, after the throttle check and before the
executor is created, add:

```java
        log.info("Scanning ports {}-{} on {} for configured UPnP device {} (no descriptor at the "
                        + "configured URL). Set port-range-start/-end to narrow this.",
                firstPort, lastPort, URI.create(staticDevice.getDescriptorUrl()).getHost(),
                udn.getIdentifierString());
```

### 4. Warn when no interface can be selected

In `configureNetworkSelection`, replace the silent early return:

```java
        if (networkInterface == null || networkInterface.isBlank()) {
            log.info("No dlna.network-interface configured; letting jUPnP select interfaces automatically");
            return;
        }
```

### 5. Document it

Add a "Configuration" section to `README.md`:

```markdown
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
```

## Do not

- Do not delete the static-device code. It solves a real problem, it just must not be on
  by default.
- Do not change `remote-device-max-age-seconds` or the `logging.level` overrides — both are
  correct and carry justifying comments.

## Verify

```bash
cd backend && mvn test && mvn spring-boot:run
```

On a machine that is **not** on the 10.0.0.x network, confirm the log shows the
auto-selection message, discovery still finds real devices, and no port-scan line appears.
Then re-pin the interface and confirm the override still works:

```bash
NETWORK_INTERFACE=eth0 mvn spring-boot:run
```

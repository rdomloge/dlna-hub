# Step 12 — Clamp seek targets to a valid range

**Phase:** 1 — Correctness
**Severity:** Medium (report: M11, M15)
**Files:** `backend/src/main/java/com/dlnahub/service/AvTransportService.java`,
`backend/src/test/java/com/dlnahub/service/` (new `AvTransportServiceTest.java`)
**Depends on:** —

## Problem

Two related gaps in `AvTransportService`:

1. `formatTime` does no range checking:

   ```java
   public static String formatTime(int totalSeconds) {
       int hours = totalSeconds / 3600;
       int minutes = (totalSeconds % 3600) / 60;
       int seconds = totalSeconds % 60;
       return String.format("%02d:%02d:%02d", hours, minutes, seconds);
   }
   ```

   `formatTime(-5)` produces `"00:00:-5"` — a malformed `REL_TIME` sent straight to the
   renderer. A client can trigger this with `POST /seek {"seconds": -5}`.

2. `forward()` adds 10 s with no upper bound, so it can seek past the end of the track.
   `backward()` correctly clamps at 0, but `forward()` has no equivalent.

## Change

### 1. Make `formatTime` reject negatives

```java
    public static String formatTime(int totalSeconds) {
        int clamped = Math.max(0, totalSeconds);
        int hours = clamped / 3600;
        int minutes = (clamped % 3600) / 60;
        int seconds = clamped % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
```

### 2. Clamp `seek` at the entry point too, so the intent is explicit

```java
    public void seek(String playerId, int seconds) {
        if (seconds < 0) {
            throw new DlnaException("Seek target must not be negative: " + seconds);
        }
        RemoteService service = playbackService.getAvTransportService(playerId);
        // ... rest unchanged
    }
```

### 3. Clamp `forward` against the track duration

Replace `forward()` with:

```java
    public void forward(String playerId) {
        PositionInfo info = readPositionInfoQuietly(playerId);
        int currentSeconds = parseTimeSeconds(info != null ? info.trackPosition() : null);
        int durationSeconds = parseTimeSeconds(info != null ? info.trackDuration() : null);
        int newSeconds = currentSeconds + SKIP_SECONDS;
        if (durationSeconds > 0) {
            // Stop a second short of the end: seeking exactly to the duration makes some
            // renderers stop rather than continue playing.
            newSeconds = Math.min(newSeconds, Math.max(0, durationSeconds - 1));
        }
        seek(playerId, newSeconds);
    }

    public void backward(String playerId) {
        PositionInfo info = readPositionInfoQuietly(playerId);
        int currentSeconds = parseTimeSeconds(info != null ? info.trackPosition() : null);
        seek(playerId, Math.max(0, currentSeconds - SKIP_SECONDS));
    }

    /** GetPositionInfo, or null if the renderer refuses it — skipping must not hard-fail. */
    private PositionInfo readPositionInfoQuietly(String playerId) {
        try {
            return getPositionInfo(playerId);
        } catch (DlnaException e) {
            log.warn("Failed to get position info for {}: {}", playerId, e.getMessage());
            return null;
        }
    }
```

Add the constant near the top of the class:

```java
    private static final int SKIP_SECONDS = 10;
```

Delete the now-unused `getCurrentPosition` private method.

### 4. Add a test file

Create `backend/src/test/java/com/dlnahub/service/AvTransportServiceTest.java`:

```java
package com.dlnahub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvTransportServiceTest {

    @Test
    void formatsSecondsAsHoursMinutesSeconds() {
        assertEquals("00:00:00", AvTransportService.formatTime(0));
        assertEquals("00:00:09", AvTransportService.formatTime(9));
        assertEquals("00:01:05", AvTransportService.formatTime(65));
        assertEquals("01:00:00", AvTransportService.formatTime(3600));
        assertEquals("02:03:04", AvTransportService.formatTime(7384));
    }

    @Test
    void formatTimeClampsNegativesToZero() {
        assertEquals("00:00:00", AvTransportService.formatTime(-1));
        assertEquals("00:00:00", AvTransportService.formatTime(-3600));
    }

    @Test
    void parsesTimeStringsInEveryAcceptedShape() {
        assertEquals(7384, AvTransportService.parseTimeSeconds("02:03:04"));
        assertEquals(125, AvTransportService.parseTimeSeconds("2:05"));
        assertEquals(42, AvTransportService.parseTimeSeconds("42"));
    }

    @Test
    void parseTimeSecondsReturnsZeroForUnusableInput() {
        assertEquals(0, AvTransportService.parseTimeSeconds(null));
        assertEquals(0, AvTransportService.parseTimeSeconds(""));
        assertEquals(0, AvTransportService.parseTimeSeconds("not:a:time"));
    }
}
```

## Do not

- Do not add request-body validation here — that is step-15.
- Do not change `parseTimeSeconds`; its existing behaviour is what the tests above assert.

## Verify

```bash
cd backend && mvn test -Dtest=AvTransportServiceTest
cd backend && mvn test
```

Then, against the real renderer: press Forward repeatedly near the end of a track and
confirm playback does not stop or jump to zero.

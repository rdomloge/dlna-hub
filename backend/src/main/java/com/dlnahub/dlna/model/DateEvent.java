package com.dlnahub.dlna.model;

/**
 * A single event on the container effective-date SSE stream (see
 * {@code ContentBrowseService.openDateStream}).
 *
 * <p>A per-container event carries the container id, its (possibly null) effective date and
 * {@code complete=true} once its subtree crawl finished. The terminal event carries
 * {@code allDone=true} and no id, telling the client every container in the folder now has a
 * known date and the stream is closing.
 */
public record DateEvent(String id, String effectiveDate, boolean complete, boolean allDone) {

    /** A per-container event: this container's subtree crawl finished and its date is known. */
    public static DateEvent dateEvent(String id, String effectiveDate, boolean complete) {
        return new DateEvent(id, effectiveDate, complete, false);
    }

    /** The terminal event: every container in the folder has a known date; the stream is done. */
    public static DateEvent terminalEvent() {
        return new DateEvent(null, null, false, true);
    }
}

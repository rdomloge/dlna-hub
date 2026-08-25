package com.dlnahub.subtitle;

import java.util.List;

/**
 * One subtitle cue.
 *
 * <p>Times are milliseconds from the start of the media. The frontend's sync engine works in
 * milliseconds, so converting once here beats converting on every animation frame.
 *
 * <p>Lines are kept separate rather than joined: the UI decides how to lay them out, and the
 * read-aloud feature wants to speak them as a single utterance. A line break in a subtitle is
 * layout for a screen, not sentence structure.
 */
public record SubtitleCue(int index, long startMs, long endMs, List<String> lines) {
}

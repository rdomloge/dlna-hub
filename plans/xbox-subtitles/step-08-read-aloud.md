# Step 08 — Read aloud

**Phase:** 2 — The second screen
**Files:** `frontend/src/hooks/useSpeech.ts` (new),
`frontend/src/components/SubtitlePanel.tsx`
**Depends on:** step-07

## Problem

The point of this feature is not looking at the phone. Speak each cue as it becomes current,
so you can keep watching the television.

The browser can do this without any dependency — `window.speechSynthesis` is in every mobile
browser worth supporting. The difficulty is not making it speak; it is stopping it becoming
a mess when cues arrive faster than speech completes.

## Change

### The hook

```ts
export function useSpeech(): {
  supported: boolean;
  enabled: boolean;
  enable: () => void;      // must be called from a user gesture
  disable: () => void;
  speak: (text: string) => void;
  rate: number;
  setRate: (rate: number) => void;
  voices: SpeechSynthesisVoice[];
  voiceUri: string | null;
  setVoiceUri: (uri: string | null) => void;
}
```

Five things this has to get right:

**1. The gesture requirement.** Mobile browsers refuse `speechSynthesis.speak()` unless it
descends from a user gesture. `enable()` is called from the button's click handler and must
speak something immediately in that same call stack to unlock the API — an empty utterance
is enough:

```ts
const enable = () => {
  window.speechSynthesis.speak(new SpeechSynthesisUtterance(''));
  setEnabled(true);
};
```

Without this the first few cues are silently swallowed and it looks broken.

**2. Backlog is the enemy.** Cues can be a second apart; a spoken line can take three. The
default `speak()` queues, so within a minute the voice is a minute behind the film and
reading dialogue from a scene that has ended.

**Cancel, do not queue:**

```ts
const speak = (text: string) => {
  if (!enabled || !text) return;
  window.speechSynthesis.cancel();     // abandon whatever is still going
  const u = new SpeechSynthesisUtterance(text);
  u.rate = rate;
  if (voice) u.voice = voice;
  window.speechSynthesis.speak(u);
};
```

Cutting a line off mid-word to start the current one is right. Being thirty seconds behind
is not.

**3. Rate.** Default to **1.3**. Subtitles are written to be read faster than they are
spoken, and default-rate TTS cannot keep up with dialogue. Expose a slider from 0.8 to 2.0
and persist it in `localStorage` — this is a genuine per-person preference, and unlike the
subtitle offset it is global rather than per item.

**4. Voice and language.** `getVoices()` is asynchronous on some browsers and returns empty
on first call — subscribe to `voiceschanged` as well as calling it directly. This matters
more than it looks: `FINDINGS.md` records that the Street Kings subtitle is **Romanian**, and
an English voice reading Romanian is unintelligible. Offer a voice picker, persist the
choice globally, and default to the browser's own default voice.

**5. Cleanup.** `speechSynthesis.cancel()` on unmount, on `disable()`, and when the tab goes
hidden. A phone that keeps talking after you close the panel is worse than one that never
started.

### Wiring it up

In `SubtitlePanel`, speak from the same place the cue index changes — the `useEffect` that
already watches it in step-07:

```ts
useEffect(() => {
  if (cueIndex < 0) return;
  speak(cues[cueIndex].lines.join(' '));
}, [cueIndex]);
```

Join the lines with a space: line breaks in an SRT are layout for a screen, not sentence
structure, and a cue split across two lines is one spoken sentence.

Do not speak when the film is paused, and do not speak a cue that became current only
because the user changed the offset — guard on `isPlaying`.

The button is a clear toggle showing state (a speaker icon with an on/off treatment, not a
label that changes meaning). Hide it entirely when `supported` is false.

## Do not

- Do not queue utterances. See point 2 — this is the mistake that makes the feature useless.
- Do not add a translation layer. If the only subtitle for a film is Romanian, this feature
  reads Romanian. Translation is a different feature with a different cost, and the honest
  fix for that film is a different subtitle file (`FINDINGS.md`, open question 1).
- Do not use a TTS service over the network. The Web Speech API is local, free, and works
  offline; a cloud voice would add a round trip to every cue and a key to manage.
- Do not speak the dimmed previous/next lines. Only the current cue.
- Do not auto-enable on panel open. It has to be a deliberate press — both because of the
  gesture requirement and because a phone that starts talking on its own is alarming.

## Verify

```bash
cd frontend && npm run typecheck && npm test && npm run lint
```

The hook is awkward to unit-test (jsdom has no `speechSynthesis`); a small mock covering
"cancel is called before every speak" is worth having and is the behaviour most likely to
regress. Do not chase coverage past that — the rest is device behaviour.

On a real phone, with a film playing:

1. Press the button. The first cue should be spoken. If nothing happens, the gesture unlock
   (point 1) is missing.
2. Let it run through a fast exchange of dialogue. Each new cue must cut off the previous
   one, and speech must stay level with the film rather than falling behind.
3. Turn the television's volume down and check it is actually usable as described — this is
   the feature's real acceptance test and it cannot be judged from the code.
4. Close the panel mid-sentence. Speech stops immediately.
5. Lock the phone. Speech stops.

Test on the phone you will actually use. iOS Safari and Chrome on Android differ in voice
availability, in when `voiceschanged` fires, and in how they treat speech with the screen
locked.

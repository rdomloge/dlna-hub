import { useCallback, useEffect, useRef, useState } from 'react';

const RATE_KEY = 'subtitle-speech-rate';
const VOICE_KEY = 'subtitle-speech-voice';

/** Subtitles are written to be read faster than they are spoken; 1.0 cannot keep up. */
const DEFAULT_RATE = 1.3;

function readStoredRate(): number {
  try {
    const raw = localStorage.getItem(RATE_KEY);
    const parsed = raw === null ? NaN : Number(raw);
    return Number.isFinite(parsed) && parsed >= 0.5 && parsed <= 2.5 ? parsed : DEFAULT_RATE;
  } catch {
    return DEFAULT_RATE;
  }
}

function readStoredVoice(): string | null {
  try {
    return localStorage.getItem(VOICE_KEY);
  } catch {
    return null;
  }
}

/**
 * Speaks subtitle cues aloud through the browser's own speech synthesiser.
 *
 * Local, free and offline — a cloud voice would add a round trip to every cue. The two
 * things that make or break it are the gesture unlock and the refusal to queue; both are
 * explained where they happen.
 */
export function useSpeech() {
  const supported =
    typeof window !== 'undefined' && typeof window.speechSynthesis !== 'undefined';

  const [enabled, setEnabled] = useState(false);
  const [rate, setRateState] = useState<number>(readStoredRate);
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [voiceUri, setVoiceUriState] = useState<string | null>(readStoredVoice);

  const enabledRef = useRef(enabled);
  const rateRef = useRef(rate);
  const voiceUriRef = useRef(voiceUri);
  const voicesRef = useRef<SpeechSynthesisVoice[]>([]);

  useEffect(() => {
    enabledRef.current = enabled;
  }, [enabled]);
  useEffect(() => {
    rateRef.current = rate;
  }, [rate]);
  useEffect(() => {
    voiceUriRef.current = voiceUri;
  }, [voiceUri]);

  // getVoices() is empty on first call in some browsers and fills in asynchronously, so the
  // event matters as much as the direct call.
  useEffect(() => {
    if (!supported) return;

    const load = () => {
      const available = window.speechSynthesis.getVoices();
      voicesRef.current = available;
      setVoices(available);
    };
    load();
    window.speechSynthesis.addEventListener('voiceschanged', load);
    return () => window.speechSynthesis.removeEventListener('voiceschanged', load);
  }, [supported]);

  const cancel = useCallback(() => {
    if (!supported) return;
    window.speechSynthesis.cancel();
  }, [supported]);

  const enable = useCallback(() => {
    if (!supported) return;
    // Mobile browsers only unlock speech from inside a user gesture, and the unlock has to
    // happen in this call stack. Without this the first few cues are silently swallowed.
    window.speechSynthesis.speak(new SpeechSynthesisUtterance(''));
    setEnabled(true);
  }, [supported]);

  const disable = useCallback(() => {
    cancel();
    setEnabled(false);
  }, [cancel]);

  const speak = useCallback(
    (text: string) => {
      if (!supported || !enabledRef.current || !text) return;

      // Cancel rather than queue. Cues can be a second apart and a spoken line can take
      // three; queueing puts the voice further behind the film with every line until it is
      // reading a scene that ended a minute ago. Clipping a line is the lesser evil.
      window.speechSynthesis.cancel();

      const utterance = new SpeechSynthesisUtterance(text);
      utterance.rate = rateRef.current;
      const chosen = voicesRef.current.find((v) => v.voiceURI === voiceUriRef.current);
      if (chosen) {
        utterance.voice = chosen;
        utterance.lang = chosen.lang;
      }
      window.speechSynthesis.speak(utterance);
    },
    [supported]
  );

  const setRate = useCallback((value: number) => {
    setRateState(value);
    try {
      localStorage.setItem(RATE_KEY, String(value));
    } catch {
      // A browser with storage blocked still speaks; it just forgets the preference.
    }
  }, []);

  const setVoiceUri = useCallback((value: string | null) => {
    setVoiceUriState(value);
    try {
      if (value === null) localStorage.removeItem(VOICE_KEY);
      else localStorage.setItem(VOICE_KEY, value);
    } catch {
      // As above.
    }
  }, []);

  // A phone that carries on talking after the panel closes is worse than one that never started.
  useEffect(() => cancel, [cancel]);

  return {
    supported,
    enabled,
    enable,
    disable,
    speak,
    cancel,
    rate,
    setRate,
    voices,
    voiceUri,
    setVoiceUri,
  };
}

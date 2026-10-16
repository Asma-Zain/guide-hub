# Speech-to-text backends

Both sit behind `voice/SpeechToText.kt`; select in Settings → Speech recognition.

| | Android `SpeechRecognizer` (default) | Vosk (offline) |
|---|---|---|
| Code | `voice/AndroidSpeechToText.kt` | `voice/VoskSpeechToText.kt` |
| Network | `EXTRA_PREFER_OFFLINE`; tries the on-device recogniser first (API 31+), then the system recogniser. Some phones still go online if no offline en-IN pack is installed. | Never |
| Accuracy (free speech) | Best, Indian-English accent support is good when the en-IN pack is installed | Lower on open vocabulary |
| Accuracy (room list) | Good | Very good: the grammar is constrained to room names, aliases, command and number words |
| Noise handling | Engine-controlled; we cannot add NoiseSuppressor | We own the `AudioRecord` (VOICE_COMMUNICATION source) and enable `NoiseSuppressor` + `AutomaticGainControl` where available |
| Confidence | Often missing (0/−1) → treated as 0.75 | Word-level confidences averaged |
| Size | 0 (system) | ~36 MB model (`vosk-model-small-en-in-0.4`) |
| Earbud mic | Follows the communication-device route set by `AudioRouteManager` | Same (VOICE_COMMUNICATION source) |

## Installing the Vosk model

1. Download `vosk-model-small-en-in-0.4` from alphacephei.com/vosk/models and unzip.
2. Copy the folder contents to the app's private files dir as `vosk-model`, e.g.
   `adb push vosk-model-small-en-in-0.4 /data/local/tmp/` then
   `adb shell run-as com.guidehub cp -r /data/local/tmp/vosk-model-small-en-in-0.4 files/vosk-model`.
3. Settings → Speech recognition → VOSK. If the folder is missing the app falls back to Android STT.

## Offline en-IN for Android STT

Settings app → System → Languages → Speech / On-device recognition → download **English (India)**.
Check with the phone in airplane mode.

## Number handling

Both backends feed `NumberNormalizer`, which turns "two oh four", "two zero four", "two hundred and four",
"two forty", "double four" and "2 0 4" into digits before intent parsing and destination matching.
After two failed attempts the app asks the user to say the room number digit by digit.

## Session lifecycle (Android backend)

One tap = one session. `Orchestrator.listenOnce()`:

1. `VoiceState.Preparing` is published at once (the UI shows "Getting ready").
2. Routine TTS is stopped (`AudioFeedback.beginListening`), the LISTEN_START earcon plays, and the app waits
   180 ms (`LISTEN_CUE_MS`) so the cue is neither captured nor cut by the HFP switch.
3. `AudioRouteManager.openMic()` routes the earbud or phone mic and waits the 300 ms settle time.
4. `AndroidSpeechToText.listen()` runs one recogniser session on the main thread. `onReadyForSpeech`,
   `onBeginningOfSpeech`, partial results and `onEndOfSpeech` drive `VoiceState.Listening` / `Processing`.
5. The final text goes to `IntentParser` → `DestinationMatcher` → a destination proposal (confirm with yes,
   the Start button, or by choosing one of several alternatives).

Fallback chain inside one session: on-device recogniser (en-IN) → system recogniser (en-IN, offline
preferred) → system recogniser (phone language). The next backend is tried only if the previous one failed
*before any speech was heard* (language pack missing, service could not start), so the user never has to
repeat themselves for one tap. If the on-device recogniser rejects en-IN once, it is skipped for the rest of
the process.

Errors carry a kind (`SttErrorKind`): permission (offer "Allow microphone"), unavailable/network (use the
typed field), busy/audio/other (try again). A second tap, the Cancel button, a CRITICAL hazard or locking the
app cancels the session; recogniser cleanup always runs on the main thread.

## Defects fixed (October 2026)

* **Simulator fake replaced the real recogniser.** In simulator mode (the default) Tap to Talk used the
  scripted test STT whenever `isRecognitionAvailable()` was false, and for the 45 s the demo job stayed
  alive. The mic never opened; the user heard "I didn't hear anything". The fake is now used only while the
  demo script is playing.
* **Recogniser leaked on cancel.** Cancelling from the orchestrator thread called `SpeechRecognizer.cancel()`
  / `destroy()` off the main thread; the exception was swallowed and the recogniser leaked, so the next
  session failed as busy.
* **No fallback from the on-device recogniser**, which commonly lacks the en-IN pack (errors 12/13).
* **No way to recover from a denied microphone permission.**
* UI showed "Listening" late (after the route settle) and had no live transcript; the start cue overlapped
  recogniser start-up.

Not verifiable without a phone: which recogniser each test phone ends up using, and recognition accuracy.
Diagnostics → Device connection status → Speech recognition shows the recogniser that actually ran.

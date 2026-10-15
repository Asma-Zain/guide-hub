# Speech-to-text backends

Both sit behind `voice/SpeechToText.kt`; select in Settings → Speech recognition.

| | Android `SpeechRecognizer` (default) | Vosk (offline) |
|---|---|---|
| Code | `voice/AndroidSpeechToText.kt` | `voice/VoskSpeechToText.kt` |
| Network | `EXTRA_PREFER_OFFLINE`; API 31+ uses the on-device recogniser when present. Some phones still go online if no offline en-IN pack is installed. | Never |
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

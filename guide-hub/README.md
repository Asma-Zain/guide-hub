# Guide Hub — phone hub for assistive indoor navigation

Android app (Kotlin, Jetpack Compose) that coordinates a headband (Pi Zero 2 W: camera + scanning ToF), an ESP32
tactile compass and Bluetooth earbuds to guide a visually impaired user indoors. It is audio-first and haptic-first,
and it locks behind device authentication. Implements `../PRD.md`.

## Build & test

Requires JDK 17 and the Android SDK (platform 36).

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

The unit tests include a full simulated corridor run. It goes from a spoken request through confirmation, routing and
marker fixes, a person stepping into the corridor and a compass drop, to arrival. It runs in virtual time with the
real codecs, links and estimators.

## Running without hardware

Simulator mode is **on** by default (Settings → Simulator mode). Unlock the app and accept the safety disclaimer.
Then either:

* **Guide tab → Run demo scenario.** This plays `assets/scenarios/corridor_demo_scenario.json`: "take me to the
  lab" → "Did you mean Lab 204 or Lab 240?" → "the first one" → walk, person ahead, compass drop, arrival.
* Type a destination ("take me to the library") or tap a destination button. The simulated user walks the route.
* Or tap the big button and speak.

The **Debug** tab shows link status, the position estimate against the simulated truth on the map, the ToF polar
plot with wall fit and hazards, the compass panel with its command/ACK log, and the event-bus tail. It also has
buttons to inject a person or drop the compass or headband.

## Real hardware

1. Settings → Simulator mode **off**.
2. Compass: flash firmware per `docs/esp32_contract.md`. The app scans for the service UUID, or you can set the BLE
   address in Settings.
3. Headband: implement `docs/pi_contract.md`. Join the phone's hotspot and connect to the URL and pairing token shown
   on the Debug tab.
4. Calibrate the camera (`docs/calibration.md`) and print AprilTags (36h11, 15 cm) at the map's marker nodes.
5. Optional: add an object-detector model (`docs/models.md`) and the Vosk model (`docs/STT.md`).

## Layout

```
app/src/main/java/com/guidehub/
  auth/       AuthManager (auto-lock, screen-off), BiometricGate
  core/       Orchestrator, EventBus, NavigationSession, Settings, AppContainer, GuideService, Constants
  voice/      SpeechToText (+ Android, Vosk), IntentParser, NumberNormalizer, DestinationMatcher, PushToTalk
  nav/        MapModel/Validator, MapGraph, Router (A*), PositionEstimator, WallHeadingEstimator, VisualOdometry,
              NavigationController, HeadingSource
  vision/     MarkerDetector (ArUco/AprilTag/QR + PnP), FeatureTracker (LK), ObjectDetector (TFLite),
              HazardEngine, DistanceFusion, FrameProcessor
  audio/      AudioFeedback, PriorityArbiter, EarconPlayer/HazardBeeper, ToneSynth, AudioRouteManager, AndroidTts
  link/       BleTransport (+ Android), CompassLink, HeadbandLink, LinkSupervisor, WsServer
  protocol/   Packet codecs (compass, headband, frame header)
  sim/        MockCompassTransport, MockHeadbandTransport, SimWorld, ScenarioPlayer, ScriptedSpeechToText
  recording/  SessionRecorder / SessionReplayer (JSONL)
  debug/      DebugScreen
  ui/         MainActivity, screens, settings
app/src/main/assets/  maps/ (corridor_demo.json, map.schema.json), scenarios/, calibration/, models/
docs/          DECISIONS, esp32_contract, pi_contract, STT, audio_routing, calibration, models, test_report_template
```

## Milestone status

| Milestone | Status |
|---|---|
| M0 Skeleton (event bus, settings, simulator, foreground service, CI) | Done |
| M1 Auth lock (BiometricPrompt, auto-lock, FLAG_SECURE, locked notification) | Done; instrumented test not yet run on a device |
| M2 Protocol + CompassLink (ACK/retry/degrade, ≤ 5 Hz, ring-buffer log, debug panel) | Done; real-ESP32 test pending hardware |
| M3 Map, A*, TTS + priority arbiter, typed navigation | Done |
| M4 Voice & audio routing (earbud HFP window, MediaSession PTT, Android/Vosk STT, confirmation) | Done; per-earbud testing pending |
| M5 Position & heading (wall fit, VO, fusion, marker correction) | Done and tested on synthetic data and the simulator |
| M6 Vision (frames over WS, ArUco/QR + pose, overlay) | Done; needs real frames and calibration |
| M7 Hazards (ToF + detector fusion, spatial cues, cooldown) | Done; detector model not bundled |
| M8 Integration & degradation (failure matrix, reconnect, record/replay) | Done |
| M9 Polish (disclaimer, TalkBack labels, test report template) | Partial: battery tuning and on-device TalkBack pass need hardware |

See `docs/DECISIONS.md` for the defaults chosen where the PRD was ambiguous, and for the known gaps.

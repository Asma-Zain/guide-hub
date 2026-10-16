# Decisions log

Where the PRD was ambiguous, the stated default was taken (PRD §15). Each entry: decision, and why.

## Platform & structure
1. **Single Gradle module, packages per PRD §9** (`auth`, `core`, `voice`, `nav`, `vision`, `audio`, `link`,
   `protocol`, `sim`, `debug`, `recording`, `ui`). Manual DI (`core/AppContainer.kt`) instead of Hilt — the PRD
   marks Hilt optional and it keeps the build small.
2. **AGP 8.13 / Kotlin 2.1.21 / compileSdk 36 / targetSdk 35 / minSdk 29.**
3. **Pure logic is Android-free** (codecs, parser, matcher, router, estimators, hazard engine, arbiter, orchestrator)
   so it is unit-tested on the JVM; Android classes are thin adapters behind interfaces
   (`BleTransport`, `SpeechToText`, `AudioOutput`, `MicRouting`, `CompassControl`, `FrameSource`, `ObjectDetector`).

## Conventions
4. **Map frame**: x = east, y = north (metres); headings clockwise from +y (compass style). This matches the PRD
   sample (`N1→N2`, `headingDeg 90`, room at larger x).
5. **Marker `facingDeg`** = the heading of a user looking at the tag head-on (viewing direction).
   User yaw = `facingDeg − markerYawDeg` (PRD: "marker's known facing direction minus the observed orientation").
6. **`MarkerDetection.markerYawDeg`** was added (PRD §6 FR-6 lists only id/range/bearing, but FR-5 needs the
   orientation). The Pi BLE `MARKER` packet accepts an optional trailing `i16 markerYaw×10` (12 bytes).

## Lock / session
7. **Locking mid-navigation** keeps `NAVIGATING`/`PAUSED` with a `locked` overlay (FR-1/§10: navigation audio
   continues, voice & UI blocked). Locking from any other state moves to `LOCKED`.
8. **API 29 authenticators**: `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` is unsupported on API 29, so API 29 uses
   `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`. No custom PIN store; repeated failures use system lockout.
9. **Links while locked**: BLE peripherals are released when locked unless navigation is active
   (`Orchestrator.linksWanted`). An intentional release is never announced as a link loss.

## Voice
10. **Push-to-talk mapping**: earbud play/pause (incl. HEADSETHOOK) by default; NEXT/PREVIOUS selectable (many
    earbuds map double-tap to NEXT). Volume-down = PTT only while the app is in the foreground (Android does not
    deliver volume keys to background apps without hacks).
11. **Auto re-listen**: after a question ("Did you mean…?", "please repeat") the mic re-opens automatically once
    speech has finished, at most twice per button press, so the flow is eyes-free. Configurable.
12. **STT confidence**: many recognisers return 0/−1 for "unknown"; those are treated as 0.75. Below 0.45 → reprompt.
13. **Confirmation threshold**: match ≥ 0.8 and ≥ 0.1 ahead of the runner-up = confident; else confirm by voice.
    Digit mismatches are penalised so "204" never silently becomes "240".

## Position & heading (no phone sensors)
14. **Fusion filter** is a complementary filter, not an EKF: marker fix resets pose and confidence; wall fit blends
    yaw (gain ∝ fit quality) and lateral offset; VO integrates yaw and progress. Confidence decays linearly to 0
    over 30 m walked since the last fix (2× when VO quality is poor).
15. **Wall fits that disagree with the current yaw by > 35°** are ignored — they most likely come from a different
    corridor (mid-turn at a junction).
16. **Junction turns**: when the head points > 60° off the current edge within 2.5 m of its end, the filter moves
    onto the outgoing edge aligned with the head (route edge preferred). Found necessary by the simulator test.
17. **Looking sideways** (head 60–120° off the corridor) contributes no progress.
18. **VO forward scale**: ground-plane back-projection (camera height 1.6 m) after de-rotating by the VO yaw, fused
    with the ToF forward-range change when consistent (±30 %); when inconsistent, the smaller motion is used and
    quality is reduced (a person walking toward the ToF would otherwise inflate progress).
19. **IMU**: `HeadingSource` interface with `HeadbandVisionHeading` (enabled) and a disabled `HeadbandImuHeading`
    stub. No phone-sensor source exists anywhere in the code.

## Hazards & audio
20. **Walls are not hazards**: only ToF returns inside a 0.9 m-wide walking path count.
21. **ToF-only returns are classed `HEAD` zone** (the beam is at head height), so they escalate one level per FR-7.
    Persons are `TORSO`; camera boxes entirely low in the frame are `GROUND` and are taken from the camera even
    when ToF is healthy (the head-height beam cannot see benches).
22. **Spoken hazard TTS only for labelled hazards** (PRD), unlabelled obstacles beep only.
23. **Object detector rate**: "throttle to ≥ 3 fps" read as "run at a fixed rate of at least 3 fps"; default 4 fps,
    configurable.
24. **Model not bundled**: `assets/models/detector.tflite` must be added (docs/models.md); without it the app runs
    ToF-only hazards with "obstacle" labels.

## Links
25. **Compass keep-alive**: a `PING` every 1 s when idle, because the ESP32 failsafe is 2 s.
26. **Headband heartbeat**: link considered stale after 3 s without any notification (sweeps arrive at 1–2 Hz).
27. **WebSocket auth**: `Authorization: Bearer <token>` header (not a URL query string, to keep the secret out of
    logs). Token = 128-bit random, shown on the Debug tab as text + QR.

## Simulator
28. **Simulator mode defaults ON** so the app is usable and demoable with no hardware; turn it off in Settings for
    real BLE. Switching rebuilds the link set at runtime.
29. The simulator pushes ToF sweeps through the real BLE codec + `HeadbandLink`, and emits markers/VO/objects as
    phone-side vision results (what `FrameProcessor` would produce).

## Demo-readiness pass (October 2026)
30. **Astra voice**: the requested output voice is "Astra". The only Astra voices found are cloud TTS products
    (e.g. Telnyx `NaturalHD.astra`, an ElevenLabs-backed `astra`), which need a network and an API key. That
    conflicts with the PRD (on-device only, no cloud in v1), so none was integrated. `AndroidTts` instead uses
    an Astra voice only if the phone has an *on-device* engine or voice with that name; it rejects
    network-only voices, verifies the active voice by reading it back from the engine, and otherwise uses the
    best local en-IN/en-GB/en-US voice. Settings and Diagnostics state which voice is active and why.
31. **Confirm before starting** (default on): even an unambiguous match is read back with a route-preview
    distance and needs "yes" or Start. Ambiguous matches list the alternatives; picking one starts navigation.
    Turn it off in Settings → Voice & Controls to restore the PRD's read-back-and-go behaviour.
32. **Speech rate** defaults to 0.95× and is clamped to 0.7–1.3×.
33. **Stopping navigation or a demo flushes queued speech** so no stale instruction plays afterwards.
34. **Simulator walls** are bounded segments with openings where corridors meet (the walkable area is the union
    of corridor rectangles). Infinite wall lines made the far wall appear ahead at junctions and doorways,
    causing spurious beeps at turns and after arrival. Navigation and estimation code are unchanged.
35. **Demo pacing**: 0.9 m/s walk, one person, one 6 s compass drop; the script ends at arrival. Hazard handling
    itself is not slowed.

## Not implemented in v1 (known gaps)
* Phone-camera fallback `FrameSource` (PRD §9 mentions it for testing); frames come from the headband or recordings.
* `speakDistancesInSteps` / step length are stored settings but no speech path uses them yet (the switch is
  hidden; step length is labelled as reserved).
* A brief warning can sound mid-turn at a junction when the opposite corridor wall falls inside the ±0.45 m
  walking path for one sweep. This is real ToF geometry, so it is left alone pending on-hardware tuning.
* Measured latency instrumentation at the earbud output (needs hardware; see test_report_template.md).

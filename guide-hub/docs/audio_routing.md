# Audio routing (earbuds first, phone fallback)

Implementation: `audio/AudioRouteManager.kt`, `audio/AudioFeedback.kt`, `audio/EarconPlayer.kt`, `voice/PushToTalk.kt`.

## Safety first

Blocking ambient sound is dangerous for a blind pedestrian. **Use open-ear or bone-conduction earbuds**, or in-ear
buds in transparency/ambient mode. The app never relies on masking the environment; warning volume defaults to a
moderate level (Settings → Hazard cue volume). The first-run disclaimer says this aloud.

## Output

* All speech and cues use `AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY`, so they follow the accessibility
  volume stream and route to the earbuds (A2DP / LE Audio) whenever they are connected.
* Hazard beeps are stereo-panned by bearing (±60° → full left/right). With no earbuds (phone speaker, mono) the
  direction is encoded by pitch (low = left, mid = centre, high = right) and every spoken warning includes a
  direction word.
* Earbuds disconnect → the phone vibrates, says "Earbuds disconnected. Using the phone speaker." on the speaker,
  and switches to mono cues. Reconnect is announced once.

## Input (push-to-talk window only)

Opening the earbud mic switches most Bluetooth earbuds from A2DP (stereo, high quality) to HFP (mono, 8–16 kHz),
so the app opens the mic route **only while listening**:

1. Stop/duck TTS and earcons (CRITICAL hazard speech may still play).
2. Select the earbud mic explicitly: `AudioManager.setCommunicationDevice()` on API 31+, `startBluetoothSco()` on
   API 29–30, then **verify** the active device (never assume Android picked the earbuds).
3. Wait 300 ms for the route to settle, play the "listening" earcon, recognise.
4. Play the "done" earcon, clear the communication device / stop SCO, return to `MODE_NORMAL`, wait 300 ms,
   resume speech.

If the route cannot be verified the phone mic is used and the debug log says so (`Mic route: phone`).

## Push-to-talk buttons

* Earbud button via an active `MediaSession` (Settings → PTT button: PLAY_PAUSE / HEADSET_HOOK / NEXT / PREVIOUS).
  Android delivers media buttons to the most recently active playing session; if another media app was playing
  more recently, its session wins — pause/close it.
* Headband or compass button over BLE (press / double press).
* Phone volume-down key while the app is open.
* "Tap to talk" full-screen target for sighted helpers.

## Per-earbud notes (fill in during HIL testing)

| Earbuds | Type | HFP switch glitch | Button events seen | Notes |
|---|---|---|---|---|
| _e.g. Shokz OpenMove_ | bone conduction | | | |
| | | | | |

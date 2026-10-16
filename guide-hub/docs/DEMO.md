# Demo script (no hardware needed)

Simulator mode is on by default. Everything the simulator produces is labelled **SIMULATED**; a purple
"Demo running" banner shows while the scripted journey plays.

## Before the audience arrives

1. Install the debug APK (see README) and open **Guide Hub**. Allow the microphone, nearby devices and notifications.
2. Tap **Unlock** and authenticate. Read and accept the safety notice (**I understand**).
3. Settings → Audio & Speech: check the **Spoken voice** line. It says whether Astra is active or which
   fallback voice is used. Set **Speech rate** if needed (0.95× default).
4. Optional: Settings → Audio & Speech → **Speech recognition**. The "In use" line shows which recogniser
   will run.

## Part 1: live voice (real microphone)

1. **Guide** tab → **Tap to Talk**. The button turns dark teal: "Getting ready", then "Listening… Speak now".
2. Say "Take me to Lab 204". Your words appear live under the button.
3. The **Confirm destination** card shows *Lab 204*, the distance and a route preview
   (e.g. "Straight 23 m → Left 3 m → Arrive"). The app says "Lab 204, about 26 metres. Say yes to start, or no."
4. Say "yes" (the mic reopens by itself) or tap **Start navigation**.
5. The direction card shows the next turn, distance and progress. Tap **Stop** to end.

If recognition is unavailable on the phone, the screen says so: use **Or type it** → "Lab 204" → **Find**,
then **Start navigation**. Typed entry does exactly the same flow.

Try an ambiguous request too: "Take me to the lab" → **Which one did you mean?** with Lab 204 and Lab 240.

## Part 2: scripted journey (about a minute)

1. **Guide** tab → **Run Demo** (in the *Demo journey* card).
2. Scripted request "take me to the lab" → **Which one did you mean?** (Lab 204 / Lab 240) → scripted
   answer "the first one".
3. Navigation starts: "Navigating to Lab 204, about … metres" and a straight-ahead instruction.
4. A simulated person steps into the corridor: red **Person ahead** card, urgent beeps, "Person ahead, one metre".
   The simulated walker stops until the person moves away.
5. The simulated compass drops: amber **Compass not connected** card, one announcement. About 6 s later:
   "Compass reconnected", once.
6. "Turn left in … metres" → "Turn left now" → green **You have arrived** card.
7. **Run demo again** repeats it identically. **Stop Demo** at any point cancels the script, faults,
   obstacles and queued speech.

## Part 3: diagnostics (optional)

**Diagnostics** tab: device connection status (all marked SIMULATED), then expandable sections for the
position map, ToF sweep plot, camera, compass ACK log, simulator controls, and the event history.

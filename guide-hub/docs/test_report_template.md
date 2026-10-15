# Guide Hub — test report

Date: ____ Build: ____ Phone: ____ Android: ____ Earbuds: ____ Map: ____

## 1. Automated
| Suite | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` (protocol, parser ≥ 40 phrasings, matcher, router, estimators, hazards, arbiter, compass link, simulated corridor run) | __ / __ passed |
| `./gradlew :app:connectedDebugAndroidTest` (auth gate) | |
| `./gradlew :app:lintDebug` | |

## 2. Hardware-in-the-loop (in this order)

### 2.1 ESP32 compass alone
| Commanded (°) | −90 | −45 | 0 | 45 | 90 | 180 |
|---|---|---|---|---|---|---|
| Needle measured (°) | | | | | | |
| Error (target ±5°) | | | | | | |
ACK rate over 200 commands: ___ % Median ACK latency: ___ ms Command → needle start: ___ ms (≤ 250)

### 2.2 Headband alone
Sweep rate ___ Hz, coverage ___° to ___°, frames ___ fps at ___×___, dropped ___ %.

### 2.3 Marker detection
| Distance (m) | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| Range error (m), bright | | | | | |
| Range error (m), dim | | | | | |
| Bearing error (°) | | | | | |

### 2.4 Voice
Quiet corridor: ___ / ___ destinations correct (target ≥ 90 %). Noisy corridor: ___ / ___.

### 2.5 Hazard warnings
| Object | Distance (m) | Severity heard | Latency at phone output (ms) | Latency at earbuds (ms) |
|---|---|---|---|---|
| Person standing | 1.0 | | (≤ 300 ToF / ≤ 700 vision) | |
| Pole | 1.5 | | | |

### 2.6 Full corridor demo (3 trials)
| Trial | Success | Drift before marker (m) | Correction at marker (m) | Heading error vs taped line (°, ≤ 10) | Needle error (°, ≤ 15) | Hazard latency (ms) |
|---|---|---|---|---|---|---|
| 1 | | | | | | |
| 2 | | | | | | |
| 3 | | | | | | |

Session recordings (Settings → Record session) contain `position` lines with `corr` (marker correction, m) and
`hazards` / `spoken` lines with timestamps for these numbers.

## 3. Degradation checks
| Case | Expected | Observed |
|---|---|---|
| Compass powered off mid-route | "Compass disconnected" once, audio continues, reconnect announced | |
| Headband powered off | Announced once, hazards/markers off, nav continues at reduced confidence | |
| Earbuds off | Vibrate + speaker announcement, mono cues | |
| Phone locked while navigating | Audio continues, voice blocked | |
| Phone sensors disabled | Heading/position unaffected | |

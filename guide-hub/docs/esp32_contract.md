# ESP32 tactile compass — firmware contract

Source of truth: `app/src/main/java/com/guidehub/core/Constants.kt` and
`app/src/main/java/com/guidehub/protocol/CompassProtocol.kt`. Keep this file in sync.

## GATT service (ESP32 = peripheral, phone = central)

| Item | UUID |
|---|---|
| Service | `6e1a0001-4b3c-4f8a-9d2e-7a5c3b1f0a10` |
| `CMD` (Write without response) | `6e1a0002-4b3c-4f8a-9d2e-7a5c3b1f0a10` |
| `ACK` (Notify) | `6e1a0003-4b3c-4f8a-9d2e-7a5c3b1f0a10` |
| `STATUS` (Read / Notify) | `6e1a0004-4b3c-4f8a-9d2e-7a5c3b1f0a10` |

Advertise the service UUID so the app can find the compass by scanning. The app requests an MTU of 185.

All multi-byte values are **little-endian**.

### CMD — 6 bytes

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | opcode: `0x01 SET_BEARING`, `0x02 SET_MODE`, `0x03 CALIBRATE`, `0x04 PING` |
| 1 | u8 | seq (wraps 0..255) |
| 2 | i16 | bearingDeg, −180..180. 0 = straight ahead, +90 = right, ±180 = behind |
| 4 | u8 | mode: `0 IDLE`, `1 NAVIGATE`, `2 ARRIVED`, `3 HAZARD`, `4 CALIBRATE` |
| 5 | u8 | flags (reserved, send 0) |

Example: `SET_BEARING seq=7 bearing=-90 NAVIGATE` → `01 07 A6 FF 01 00`.

### ACK — 4 bytes (notify, one per CMD, including PING)

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | seq (echo of the command) |
| 1 | u8 | status: `0 OK`, `1 CLAMPED`, `2 BUSY`, `3 ERR` |
| 2 | i16 | actualDeg — needle position the firmware is driving to |

### STATUS — 4 bytes

`u8 battery%`, `u8 state` (current mode code), `u16 uptimeS`.

## Behaviour required from firmware

* **ACK every command** within 250 ms. The app waits 300 ms, retries once with the same `seq`, then marks the
  compass degraded and says "Compass not responding". Duplicate `seq` (a retry) must be ACKed again but executed once.
* **Needle semantics**: point to the direction the user should walk relative to the head (0 = ahead).
  The app already low-passes and rate-limits (≤ 5 Hz, ≥ 3° change), so do not add heavy filtering.
* **Servo easing** toward the target, clamp to the mechanical range (report `CLAMPED` and the clamped `actualDeg`).
* **Modes**: `NAVIGATE` point at bearing; `ARRIVED` distinct pulse/sweep pattern then rest; `HAZARD` jitter/pulse
  toward the bearing (or a stop pose); `IDLE` rest/centre; `CALIBRATE` run the zeroing routine.
* **Failsafe**: if no command (any opcode, PING counts) arrives for **2 s**, go to the rest position. The app sends a
  PING keep-alive every 1 s while connected.
* **Heartbeat**: notify `STATUS` every 2 s.
* Command → needle starts moving: ≤ 250 ms (PRD latency target).

## Bench test (HIL step 1)

Use the app's Debug tab → Compass panel with Simulator mode **off**: slider + SET_BEARING, mode buttons and the
command log (seq, attempts, ACK status, latency). Check needle accuracy ±5° at −90, −45, 0, 45, 90, 180.

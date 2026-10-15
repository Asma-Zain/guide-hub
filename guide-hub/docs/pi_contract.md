# Headband (Raspberry Pi Zero 2 W) — contract

Source of truth: `core/Constants.kt`, `protocol/HeadbandProtocol.kt`. Keep in sync.

The headband has a Camera Module V2 and a VL53L1X ToF sensor on an SG90 scanning servo. **No mic, no speaker.**
It talks to the phone over two transports:

* **BLE GATT** (BlueZ peripheral, e.g. `bluezero`): low-rate data — ToF sweeps, buttons, status, control.
* **Wi-Fi WebSocket** (client): camera frames only. The Pi joins the phone's hotspot and connects to the app.

Heavy ML must not run on the Pi. Marker detection (ArUco) and VO on the Pi are optional.

## BLE service

| Characteristic | UUID | Props |
|---|---|---|
| Service | `6e1b0001-4b3c-4f8a-9d2e-7a5c3b1f0a10` | |
| `TOF_SWEEP` | `6e1b0002-…0a10` | Notify |
| `VO` (optional) | `6e1b0003-…0a10` | Notify |
| `MARKER` (optional) | `6e1b0004-…0a10` | Notify |
| `BUTTON` | `6e1b0005-…0a10` | Notify |
| `CTRL` | `6e1b0006-…0a10` | Write (with response) |
| `STATUS` | `6e1b0007-…0a10` | Read / Notify |

All values little-endian. The phone requests MTU 185, so a sweep of up to 61 points fits one notification.

### TOF_SWEEP — `u32 ts, u8 n, n × (i8 angleDeg, u16 mm)`
* `angleDeg`: + = right of head-forward. **Cover at least −60..+60°** (the wall-fit heading needs both walls);
  5° steps (25 points) recommended. Send one notification per full sweep (1–2 Hz with an SG90).
* `mm = 0` means "no return / out of range". Use VL53L1X long-distance mode (max ≈ 4 m).
* The app treats ≥ 5 consecutive all-zero sweeps as a ToF error (camera-only hazards, raised caution).

### VO (optional) — `u32 ts, i16 dYawDeg×100, u16 dForwardMm, u8 quality(0–255)`
Head motion since the previous VO packet. If the phone runs VO on frames too, the phone's VO wins.

### MARKER (optional) — `u32 ts, u16 id, u16 rangeCm, i16 bearingDeg×10 [, i16 markerYawDeg×10]`
* 10 bytes per the PRD; the app also accepts a 12-byte form with the marker yaw (viewing-direction yaw of the tag
  relative to the camera axis, 0 = head-on). Without yaw the app cannot fix heading from that marker.

### BUTTON — `u8 buttonId, u8 event` (`0 press, 1 release, 2 long, 3 double`)
Press / double-press = push-to-talk.

### CTRL — written by the phone
`01` start sweep · `02` stop sweep · `03 i8 min i8 max` sweep range · `04 u8 fps` frame rate · `05` shutdown.

### STATUS — `u8 battery%, i8 temperatureC, u8 wifiState (0 down, 1 connecting, 2 connected)`
Notify every 2 s. The app considers the headband stale if **no notification of any kind** arrives for 3 s.

## WebSocket (phone is the server)

* URL: `ws://<phone-ip>:8765/frames` and `ws://<phone-ip>:8765/control` (host/port configurable in Settings).
* **Auth**: HTTP header `Authorization: Bearer <pairing token>` on the handshake. The token and URL are shown on
  the phone's Debug tab (text + QR `ws://ip:port|token`). Connections without it are rejected during the handshake.
* `/control` text, sent once after connecting: `{"device":"headband","fw":"1.0","caps":["frames","aruco"]}`.
  The app replies `{"ok":true}`.
* `/frames` binary messages: 17-byte header + JPEG.

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | version = 1 |
| 1 | u32 | seq |
| 5 | i64 | timestamp ms |
| 13 | u16 | width |
| 15 | u16 | height |
| 17 | … | JPEG bytes (must start `FF D8`) |

Recommended: 640×480 at 5–10 fps, JPEG quality ~70. Send only the newest frame (drop when the socket is busy);
the phone also drops stale frames.

## Reference Python sketch

```python
# pip install websockets picamera2 opencv-python
import asyncio, struct, time, websockets, cv2
from picamera2 import Picamera2

TOKEN = "<from phone debug screen>"
URL = "ws://192.168.43.1:8765/frames"

async def main():
    cam = Picamera2(); cam.configure(cam.create_video_configuration(main={"size": (640, 480)})); cam.start()
    async with websockets.connect(URL, additional_headers={"Authorization": f"Bearer {TOKEN}"}) as ws:
        seq = 0
        while True:
            ok, jpg = cv2.imencode(".jpg", cam.capture_array()[:, :, :3], [cv2.IMWRITE_JPEG_QUALITY, 70])
            hdr = struct.pack("<BIqHH", 1, seq, int(time.time() * 1000), 640, 480)
            await ws.send(hdr + jpg.tobytes()); seq += 1
            await asyncio.sleep(1 / 8)

asyncio.run(main())
```

# Camera calibration (headband Camera Module V2)

Marker range/bearing and VO scale depend on camera intrinsics. The shipped file
`app/src/main/assets/calibration/headband_cam_v2_640x480.json` contains **placeholder** values
(`"placeholder": true`): nominal IMX219 focal length scaled to 640×480, zero distortion.

## Procedure (≈ 20 minutes)

1. Print an OpenCV chessboard (9×6 inner corners, 25 mm squares) flat on card.
2. On the Pi, capture 20–30 images at the **same resolution the headband streams** (640×480), covering the whole
   field of view at various tilts and distances (0.3–1.5 m).
3. Run:

```python
import cv2, glob, json, numpy as np
pattern, square = (9, 6), 0.025
objp = np.zeros((pattern[0] * pattern[1], 3), np.float32)
objp[:, :2] = np.mgrid[0:pattern[0], 0:pattern[1]].T.reshape(-1, 2) * square
obj, img = [], []
for f in glob.glob("calib/*.jpg"):
    g = cv2.cvtColor(cv2.imread(f), cv2.COLOR_BGR2GRAY)
    ok, c = cv2.findChessboardCorners(g, pattern)
    if ok:
        obj.append(objp)
        img.append(cv2.cornerSubPix(g, c, (11, 11), (-1, -1), (3, 30, 1e-3)))
rms, K, D, _, _ = cv2.calibrateCamera(obj, img, g.shape[::-1], None, None)
print("RMS reprojection error:", rms)  # aim for < 0.5 px
json.dump({"fx": K[0,0], "fy": K[1,1], "cx": K[0,2], "cy": K[1,2], "width": g.shape[1], "height": g.shape[0],
           "distortion": D.ravel().tolist(), "cameraHeightM": 1.6, "placeholder": False},
          open("headband_cam_v2_640x480.json", "w"), indent=2)
```

4. Measure the camera height above the floor while the headband is worn and set `cameraHeightM`.
5. Replace the asset file and rebuild. Different frame sizes are handled by scaling (`CameraIntrinsics.scaledTo`).

## Markers

* Default dictionary `DICT_APRILTAG_36h11` (Settings → Marker dictionary), printed 15 cm (Settings → Marker size).
  Mount at head height, flat on the wall, facing along the corridor.
* QR fallback: encode `GH:<id>` (e.g. `GH:12`) at the same printed size.
* In the map, `marker.facingDeg` = the heading of a person looking straight at the tag.
* Check range/bearing at 1–5 m (HIL step 3) on the Debug tab → Camera.

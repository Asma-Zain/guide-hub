# Object detector model

The app uses the TFLite Task Vision `ObjectDetector` (`vision/ObjectDetector.kt`). No model is bundled.

1. Download a COCO detector **with TFLite metadata**, e.g. EfficientDet-Lite0 (`efficientdet_lite0.tflite`,
   ~4.4 MB) or SSD MobileNet v1 with metadata, from the TensorFlow / MediaPipe model pages.
2. Save it as `app/src/main/assets/models/detector.tflite` and rebuild.
3. Without the file the app logs nothing special and runs ToF-only hazards labelled "obstacle".

Labels used for hazards (others ignored) and the size assumption for camera-only range
(`range = realHeight × fy / boxHeightPx`) live in `vision/HazardEngine.kt` (`DistanceFusion.KNOWN_HEIGHTS_M`):
person 1.65 m, chair 0.9 m, door 2.0 m, bench 0.45 m, pole 2.5 m, table 0.75 m, bicycle 1.0 m, …

The detector runs at Settings → Object detector rate (default 4 fps, 2 threads). Measure CPU on the target phone
(PRD open question 4) and lower the rate if the battery target (≥ 3 h) is missed.

package com.guidehub.core

import java.util.UUID

/**
 * Single source of truth for protocol and timing constants.
 * Mirrored in docs/esp32_contract.md and docs/pi_contract.md — keep them in sync.
 */
object Constants {
    // ---- Tactile compass (ESP32) GATT service ----
    val COMPASS_SERVICE: UUID = UUID.fromString("6e1a0001-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val COMPASS_CMD: UUID = UUID.fromString("6e1a0002-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val COMPASS_ACK: UUID = UUID.fromString("6e1a0003-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val COMPASS_STATUS: UUID = UUID.fromString("6e1a0004-4b3c-4f8a-9d2e-7a5c3b1f0a10")

    // ---- Headband (Pi Zero 2 W, BlueZ) GATT service ----
    val HEADBAND_SERVICE: UUID = UUID.fromString("6e1b0001-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val HEADBAND_TOF_SWEEP: UUID = UUID.fromString("6e1b0002-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val HEADBAND_VO: UUID = UUID.fromString("6e1b0003-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val HEADBAND_MARKER: UUID = UUID.fromString("6e1b0004-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val HEADBAND_BUTTON: UUID = UUID.fromString("6e1b0005-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val HEADBAND_CTRL: UUID = UUID.fromString("6e1b0006-4b3c-4f8a-9d2e-7a5c3b1f0a10")
    val HEADBAND_STATUS: UUID = UUID.fromString("6e1b0007-4b3c-4f8a-9d2e-7a5c3b1f0a10")

    /** Client Characteristic Configuration Descriptor (enables notifications). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Requested ATT MTU so a full ToF sweep (5 + 3n bytes, n<=61) fits one notification. */
    const val BLE_MTU = 185

    // ---- Wi-Fi WebSocket (frames only) ----
    const val WS_DEFAULT_PORT = 8765
    const val WS_PATH_FRAMES = "/frames"
    const val WS_PATH_CONTROL = "/control"
    const val FRAME_HEADER_VERSION = 1

    // ---- Timing ----
    const val HEARTBEAT_MS = 2_000L
    /** Compass keep-alive; must be below the ESP32 2 s failsafe. */
    const val COMPASS_KEEPALIVE_MS = 1_000L
    const val COMPASS_ACK_TIMEOUT_MS = 300L
    const val COMPASS_MIN_INTERVAL_MS = 200L // <= 5 Hz
    const val COMPASS_MIN_DELTA_DEG = 3
    const val COMPASS_LOG_SIZE = 200
    const val ESP32_FAILSAFE_MS = 2_000L
    /** Headband is considered stale if no notification arrives within this window. */
    const val HEADBAND_STALE_MS = 3_000L
    const val MIC_ROUTE_SETTLE_MS = 300L
    /** The LISTEN_START earcon is 120 ms; wait this long after it before opening the mic. */
    const val LISTEN_CUE_MS = 180L

    // ---- Navigation ----
    const val ARRIVAL_RADIUS_M = 1.5
    const val TURN_PREANNOUNCE_M = 6.0
    const val TURN_NOW_M = 1.5
    const val MAX_MARKER_SPACING_M = 15.0
    const val LOW_CONFIDENCE = 0.4
    /** Distance walked since last marker fix at which confidence reaches 0. */
    const val CONFIDENCE_DECAY_DISTANCE_M = 30.0
    const val DEVIATION_LATERAL_M = 3.0
    const val NEEDLE_MAX_YAW_RATE_DPS = 90.0

    // ---- STT ----
    const val STT_LOW_CONFIDENCE = 0.45f
    const val STT_DEFAULT_CONFIDENCE = 0.75f

    // ---- TTS ----
    /** Requested spoken-output voice. Only used if an on-device engine actually provides it (see AndroidTts). */
    const val PREFERRED_VOICE = "Astra"
    const val TTS_RATE_MIN = 0.7f
    const val TTS_RATE_MAX = 1.3f

    const val EVENT_TAIL_SIZE = 300
}

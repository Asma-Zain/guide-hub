package com.guidehub.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

enum class AudioRoutePref { EARBUDS_PREFERRED, PHONE_ONLY }
enum class PttButton { PLAY_PAUSE, HEADSET_HOOK, NEXT, PREVIOUS }
enum class SttBackend { ANDROID, VOSK }

/** FR-10 settings. Defaults follow the PRD; see docs/DECISIONS.md for choices made here. */
@Serializable
data class AppSettings(
    val audioRoute: AudioRoutePref = AudioRoutePref.EARBUDS_PREFERRED,
    val pttButton: PttButton = PttButton.PLAY_PAUSE,
    val volumeKeyPtt: Boolean = true,
    /** Used only to phrase distances in steps for users who prefer it. Not used for positioning. */
    val stepLengthM: Double = 0.7,
    val speakDistancesInSteps: Boolean = false,
    val criticalM: Double = 1.0,
    val warningM: Double = 2.0,
    val infoM: Double = 3.5,
    val hazardCooldownMs: Long = 3_000,
    val hazardVolume: Float = 0.5f,
    val ttsRate: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val sttBackend: SttBackend = SttBackend.ANDROID,
    val markerDictionary: String = "DICT_APRILTAG_36h11",
    val markerSizeM: Double = 0.15,
    val frameRate: Int = 8,
    val objectDetectorFps: Int = 4,
    val wsHost: String = "0.0.0.0",
    val wsPort: Int = Constants.WS_DEFAULT_PORT,
    val autoLockTimeoutS: Int = 60,
    val mapAsset: String = "corridor_demo.json",
    val simulatorMode: Boolean = true,
    val compassAddress: String? = null,
    val headbandAddress: String? = null,
    val recordSession: Boolean = false,
    val recordFrames: Boolean = false,
    val autoRelisten: Boolean = true,
    val disclaimerAccepted: Boolean = false,
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
    /** WebSocket pairing token, generated on first use. */
    val pairingToken: String
    fun regeneratePairingToken(): String
}

class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val _s = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _s.asStateFlow()
    override fun update(transform: (AppSettings) -> AppSettings) { _s.value = transform(_s.value) }
    override var pairingToken: String = "test-token"; private set
    override fun regeneratePairingToken() = newToken().also { pairingToken = it }

    companion object {
        fun newToken(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Persists settings as one JSON blob in app-private SharedPreferences. */
class PrefsSettingsRepository(context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences("guidehub_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _s = MutableStateFlow(load())
    override val settings: StateFlow<AppSettings> = _s.asStateFlow()

    private fun load(): AppSettings =
        prefs.getString("settings", null)?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()

    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_s.value)
        prefs.edit().putString("settings", json.encodeToString(AppSettings.serializer(), next)).apply()
        _s.value = next
    }

    override val pairingToken: String
        get() = prefs.getString("pairing_token", null) ?: regeneratePairingToken()

    override fun regeneratePairingToken(): String {
        val t = InMemorySettingsRepository.newToken()
        prefs.edit().putString("pairing_token", t).apply()
        return t
    }
}

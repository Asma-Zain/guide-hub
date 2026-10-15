package com.guidehub.auth

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.guidehub.R

/**
 * System authentication (fingerprint / face / PIN / pattern). Repeated failures fall through to
 * the system lockout behaviour; this class never stores credentials.
 */
class BiometricGate(private val activity: FragmentActivity, private val auth: AuthManager) {

    /** BIOMETRIC_STRONG | DEVICE_CREDENTIAL is unsupported on API 29, so use WEAK there. */
    private val authenticators: Int =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Null when the device can authenticate, otherwise a reason to show/speak. */
    fun unavailableReason(): String? = when (BiometricManager.from(activity).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set up a screen lock (PIN, pattern, fingerprint or face) on this phone to use Guide Hub."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Device authentication is not available right now."
        else -> "Device authentication is not available on this phone."
    }

    fun prompt(onError: (String) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = auth.onAuthenticated()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) onError(errString.toString())
                else onError("Authentication cancelled.")
            }
            // onAuthenticationFailed: a single non-matching attempt; the system prompt keeps going and
            // enforces its own lockout after repeated failures.
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.auth_title))
            .setSubtitle(activity.getString(R.string.auth_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

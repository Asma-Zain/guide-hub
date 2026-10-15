package com.guidehub.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.guidehub.core.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App lock state (FR-1). Starts locked. Re-locks when backgrounded longer than the configured
 * timeout, when the screen turns off, or on an explicit Lock action. Authentication itself is
 * delegated to the system (BiometricPrompt with device credential); no custom PIN store.
 */
class AuthManager(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val timeoutS: () -> Int,
) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var backgroundedAt: Long? = null
    private var timer: Job? = null

    fun onAuthenticated() {
        backgroundedAt = null
        timer?.cancel()
        _locked.value = false
    }

    fun lock() {
        timer?.cancel()
        _locked.value = true
    }

    fun onBackground() {
        if (_locked.value) return
        val t = clock.now()
        backgroundedAt = t
        timer?.cancel()
        timer = scope.launch {
            delay(timeoutS() * 1_000L)
            if (backgroundedAt == t) lock()
        }
    }

    fun onForeground() {
        val t = backgroundedAt ?: return
        backgroundedAt = null
        timer?.cancel()
        if (clock.now() - t > timeoutS() * 1_000L) lock()
    }

    fun onScreenOff() = lock()
}

/** Hooks [AuthManager] to process foreground/background and screen-off events. */
class AutoLock(private val context: Context, private val auth: AuthManager) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) auth.onScreenOff()
        }
    }

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = auth.onForeground()
        override fun onStop(owner: LifecycleOwner) = auth.onBackground()
    }

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

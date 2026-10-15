package com.guidehub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guidehub.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-1 acceptance (instrumented): the app opens on the lock screen and no other screen is reachable
 * without authentication, including after a notification-style relaunch of the activity.
 */
@RunWith(AndroidJUnit4::class)
class AuthGateTest {
    @get:org.junit.Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideHubApp).container

    @Test
    fun launchesLocked() {
        assertTrue(container.auth.locked.value)
        rule.onNodeWithText("Authentication required").assertIsDisplayed()
        rule.onNodeWithText("Tap to talk").assertDoesNotExist()
        rule.onNodeWithText("Debug").assertDoesNotExist()
    }

    @Test
    fun backPressDoesNotBypassLock() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun unlockShowsAppAndLockHidesItAgain() {
        rule.runOnUiThread {
            container.settings.update { it.copy(disclaimerAccepted = true) }
            container.auth.onAuthenticated()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Guide").assertIsDisplayed()
        rule.runOnUiThread { container.auth.lock() }
        rule.waitForIdle()
        rule.onNodeWithText("Authentication required").assertIsDisplayed()
        rule.onNodeWithText("Guide").assertDoesNotExist()
    }

    @Test
    fun relaunchWhileLockedStaysLocked() {
        rule.runOnUiThread { container.auth.lock() }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Authentication required").assertIsDisplayed()
    }
}

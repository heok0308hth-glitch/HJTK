package com.hjtk.app

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The real Activity, launched through the framework: if the composition root,
 * the theme, the engine and the manifest were inconsistent, this fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ActivityLaunchTest {

    @Test
    fun mainActivityStartsAndReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
                val engine = com.hjtk.app.android.Engine.of(activity)
                assertEquals(true, engine.registry.size() >= 40)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun settingsDeepLinkDoesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = android.content.Intent(context, MainActivity::class.java)
            .putExtra("settings_section", "ai")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }
}

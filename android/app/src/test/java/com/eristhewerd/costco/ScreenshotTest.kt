package com.eristhewerd.costco

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.eristhewerd.costco.ui.AppRoot
import com.eristhewerd.costco.ui.theme.CostcoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens against a demo backend (see README) and saves PNGs to
 * app/build/outputs/roborazzi. Skipped unless DEMO_SERVER and DEMO_TOKEN are set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun screens() {
        val server = System.getProperty("demo.server").orEmpty()
        val token = System.getProperty("demo.token").orEmpty()
        assumeTrue(server.isNotEmpty() && token.isNotEmpty())
        val app = ApplicationProvider.getApplicationContext<CostcoApp>()
        app.api.prefs.server = server
        app.api.prefs.token = token
        app.api.prefs.lastEventId = 0
        androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())

        compose.setContent { CostcoTheme { AppRoot(app.api, null) {} } }
        fun shot(name: String, waitFor: String) {
            compose.waitUntil(20_000) { compose.onAllNodes(hasText(waitFor, substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        }
        shot("1-spending", "By department")
        compose.onNodeWithText("Items").performClick()
        shot("2-items", "bought")
        compose.onNodeWithText("Price ↑").performClick()
        shot("3-price-up", "KS COFFEE")
        compose.onNodeWithText("KS COFFEE").performClick()
        shot("4-item", "Shelf price")
        onNodeWithContentDescriptionSafe("Back")
        compose.onNodeWithText("Savings").performClick()
        shot("5-savings", "Break-even")
        compose.onNodeWithText("Receipts").performClick()
        shot("6-receipts", "HUNTSVILLE")
        onNodeWithContentDescriptionSafe("Settings")
        shot("7-settings", "Sync now")
    }

    private fun onNodeWithContentDescriptionSafe(d: String) {
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasContentDescription(d)).performClick()
    }
}

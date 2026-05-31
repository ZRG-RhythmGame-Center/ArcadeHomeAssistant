package com.maimai.home

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * Smoke test for the Compose UI test stack on instrumented devices.
 *
 * Wave 2.11 — exercises:
 * - `androidx.compose.ui:ui-test-junit4` resolves the
 *   [createComposeRule] entry point on the `androidTest` classpath.
 * - `setContent { ... }` actually composes.
 * - Hierarchy assertions like `onNodeWithText(...).assertIsDisplayed()` work.
 *
 * Runs against an emulator or device via `gradlew connectedDebugAndroidTest`.
 * It does NOT cover the production Composables — those tests land in
 * Wave 5 once the screens are refactored.
 */
class ComposeSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersStaticText() {
        composeRule.setContent {
            Text(text = "compose-smoke")
        }
        composeRule.onNodeWithText("compose-smoke").assertIsDisplayed()
    }
}

package com.maimai.home

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test that Robolectric is on the classpath, can boot a sandboxed
 * Android runtime under JUnit 4 (bridged via `junit-vintage-engine`), AND
 * actually wires up the production [App] class declared in the manifest.
 *
 * Wave 2.10 — exercises:
 * - Robolectric runner can stand up the SDK shadow.
 * - `ApplicationProvider.getApplicationContext<App>()` returns the real
 *   production [App] class. Asserting `instanceOf App` catches a regressed
 *   `<application android:name=".App">` entry, which a generic
 *   `Application`-typed lookup would miss.
 * - The package name matches one of the two known build-variant-suffixed
 *   values (debug adds `.debug`, release does not).
 * - JUnit 4 tests still execute under the JUnit Platform via the Vintage
 *   engine added in Wave 2.7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RobolectricSmokeTest {

    @Test
    fun robolectricBootsAndExposesApplicationContext() {
        val app = ApplicationProvider.getApplicationContext<App>()
        // Catch regressed manifest wiring: the production App class must be
        // the application instance, not Robolectric's generic Application.
        assertThat(app).isInstanceOf(App::class.java)
        // Debug variant suffixes the application id with `.debug`. Allow
        // both ids so the assertion stays correct against either variant.
        assertThat(app.packageName).isAnyOf(
            "com.maimai.home",
            "com.maimai.home.debug",
        )
    }
}

package com.maimai.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test that Robolectric is on the classpath and can boot a sandboxed
 * Android runtime under JUnit 4 (bridged via `junit-vintage-engine`).
 *
 * Wave 2.10 — exercises:
 * - Robolectric runner can stand up the SDK shadow.
 * - `ApplicationProvider.getApplicationContext` returns the real test
 *   `Application` (Robolectric default), with the production package name.
 * - JUnit 4 tests still execute under the JUnit Platform via the Vintage
 *   engine added in Wave 2.7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RobolectricSmokeTest {

    @Test
    fun robolectricBootsAndExposesApplicationContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context).isNotNull()
        // Debug variant suffixes the application id with `.debug`. Production
        // builds use the bare `com.maimai.home` package; assert against the
        // shared prefix to keep the test working on either variant.
        assertThat(context.packageName).startsWith("com.maimai.home")
    }
}

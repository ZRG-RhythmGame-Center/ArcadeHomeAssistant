package com.maimai.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 5 task 37: ServiceLocator OkHttpClient upload/download timeout must be
 * 5 minutes (300 seconds), not 30 seconds (R2 B6).
 *
 * RED: fails before the ServiceLocator change (readTimeout was 30 s).
 * GREEN: passes after readTimeout is raised to 300 s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceLocatorTest {

    @Test
    fun okHttpUploadDownloadTimeoutIs5min() {
        // Initialise with a dummy context so the lazy can resolve.
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        ServiceLocator.init(context)

        val client = ServiceLocator.okHttpClient

        // readTimeout must be 5 minutes = 300_000 ms (R2 B6).
        assertThat(client.readTimeoutMillis).isEqualTo(300_000)
        // writeTimeout must also be 5 minutes (was already 300 s, keep it).
        assertThat(client.writeTimeoutMillis).isEqualTo(300_000)
    }
}

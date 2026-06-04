package com.maimai.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ServiceLocator OkHttpClient upload/download timeout must stay at 5 minutes
 * for large file transfers.
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

        // readTimeout must be 5 minutes = 300_000 ms.
        assertThat(client.readTimeoutMillis).isEqualTo(300_000)
        // writeTimeout must also be 5 minutes (was already 300 s, keep it).
        assertThat(client.writeTimeoutMillis).isEqualTo(300_000)
    }
}

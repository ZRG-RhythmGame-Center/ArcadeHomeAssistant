package com.maimai.home

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Smoke check that the JUnit 5 (Jupiter) engine is wired up and Truth
 * assertions are on the test classpath.
 *
 * Exercises:
 * - `org.junit.jupiter.api.Test` resolves (Jupiter API on test classpath).
 * - `org.junit.jupiter.engine` runs the test (engine on `testRuntimeOnly`).
 * - `com.google.truth.Truth.assertThat` resolves (Truth on test classpath).
 */
class SmokeTest {

    @Test
    fun jupiterAndTruthAreOnTheClasspath() {
        val sum = (1..3).sum()
        assertThat(sum).isEqualTo(6)
    }
}

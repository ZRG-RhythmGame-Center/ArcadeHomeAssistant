package com.maimai.home.data.models

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Characterizes [AgentStatus] / [Capabilities] JSON parsing under the same
 * `Json` configuration used in production (`ServiceLocator.json`):
 *
 * ```
 * Json {
 *     ignoreUnknownKeys = true
 *     explicitNulls = false
 * }
 * ```
 *
 * Closes R1 #18 (forward-compat) and R2 I18 (unknown capability keys silently
 * dropped) and R2 I20 (AgentStatus.baseUrl now exposed).
 *
 * Wave 3 task 18.
 */
class AgentStatusSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun parsesFullStatus() {
        val raw = """
            {
              "machineName": "PC-01",
              "version": "1.2.3",
              "uptimeSeconds": 4242,
              "capabilities": {
                "audioVolume": true,
                "audioMute": true,
                "audioDeviceSwitch": true,
                "fileManagement": true,
                "discoveryBroadcast": true
              },
              "baseUrl": "http://192.168.1.5:8765"
            }
        """.trimIndent()

        val status = json.decodeFromString(AgentStatus.serializer(), raw)

        assertThat(status.machineName).isEqualTo("PC-01")
        assertThat(status.version).isEqualTo("1.2.3")
        assertThat(status.uptimeSeconds).isEqualTo(4242)
        assertThat(status.baseUrl).isEqualTo("http://192.168.1.5:8765")
        assertThat(status.capabilities.audioVolume).isTrue()
        assertThat(status.capabilities.audioMute).isTrue()
        assertThat(status.capabilities.audioDeviceSwitch).isTrue()
        assertThat(status.capabilities.fileManagement).isTrue()
        assertThat(status.capabilities.discoveryBroadcast).isTrue()
    }

    @Test
    fun unknownCapabilityField_isIgnored() {
        // Future server adds a new capability flag the client does not know
        // about. With ignoreUnknownKeys=true the parse must succeed and the
        // known fields must keep their default / explicit values.
        val raw = """
            {
              "machineName": "PC-01",
              "version": "1.2.3",
              "uptimeSeconds": 0,
              "capabilities": {
                "audioVolume": true,
                "thisIsACapabilityFromTheFuture": true,
                "anotherOne": "not even a boolean"
              }
            }
        """.trimIndent()

        val status = json.decodeFromString(AgentStatus.serializer(), raw)

        assertThat(status.capabilities.audioVolume).isTrue()
        // Defaults preserved for omitted-and-unknown:
        assertThat(status.capabilities.audioMute).isFalse()
        assertThat(status.capabilities.fileManagement).isFalse()
    }

    @Test
    fun unknownTopLevelField_isIgnored() {
        val raw = """
            {
              "machineName": "PC-01",
              "version": "1.0.0",
              "uptimeSeconds": 0,
              "capabilities": {},
              "thisIsNew": [1, 2, 3]
            }
        """.trimIndent()

        val status = json.decodeFromString(AgentStatus.serializer(), raw)

        assertThat(status.machineName).isEqualTo("PC-01")
        assertThat(status.baseUrl).isNull()
    }

    @Test
    fun baseUrlReadWhenPresent() {
        val raw = """
            {
              "machineName": "PC-01",
              "version": "1.0.0",
              "uptimeSeconds": 0,
              "capabilities": {},
              "baseUrl": "http://my-pc.local:8765"
            }
        """.trimIndent()

        val status = json.decodeFromString(AgentStatus.serializer(), raw)

        assertThat(status.baseUrl).isEqualTo("http://my-pc.local:8765")
    }

    @Test
    fun baseUrl_defaultsToNull_whenAbsent() {
        // Older agents omit baseUrl entirely. Parse must still succeed and
        // baseUrl must be null (not throw, not default to empty string).
        val raw = """
            {
              "machineName": "PC-01",
              "version": "1.0.0",
              "uptimeSeconds": 0,
              "capabilities": {}
            }
        """.trimIndent()

        val status = json.decodeFromString(AgentStatus.serializer(), raw)

        assertThat(status.baseUrl).isNull()
    }
}

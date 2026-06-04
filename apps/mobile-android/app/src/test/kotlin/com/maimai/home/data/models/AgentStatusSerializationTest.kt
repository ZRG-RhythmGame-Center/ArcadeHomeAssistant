package com.maimai.home.data.models

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** Characterizes [AgentStatus] / [Capabilities] JSON parsing under production JSON options. */
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
                "discoveryBroadcast": true,
                "remoteShutdown": true
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
        assertThat(status.capabilities.remoteShutdown).isTrue()
    }

    @Test
    fun unknownCapabilityField_isIgnored() {
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
        assertThat(status.capabilities.audioMute).isFalse()
        assertThat(status.capabilities.fileManagement).isFalse()
        assertThat(status.capabilities.remoteShutdown).isFalse()
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

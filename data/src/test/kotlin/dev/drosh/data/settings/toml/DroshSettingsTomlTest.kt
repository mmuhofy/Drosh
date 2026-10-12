package dev.drosh.data.settings.toml

import dev.drosh.core.toml.TomlDocument
import dev.drosh.domain.settings.AgentEffort
import dev.drosh.domain.settings.DroshSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `[agent]` section of the settings file.
 *
 * The file is meant to be read and written by hand from a shell, so the two
 * properties that matter here are that a value survives a round trip and that an
 * older file — one written before these keys existed — still loads.
 */
class DroshSettingsTomlTest {

    private fun roundTrip(settings: DroshSettings): DroshSettings =
        DroshSettingsToml.fromDocument(DroshSettingsToml.toDocument(settings))

    @Test
    fun `web search and effort survive a round trip`() {
        val written = DroshSettings.DEFAULT.copy(
            agent = DroshSettings.DEFAULT.agent.copy(webSearch = false, effort = AgentEffort.HIGH),
        )

        val read = roundTrip(written)

        assertFalse(read.agent.webSearch)
        assertEquals(AgentEffort.HIGH, read.agent.effort)
    }

    @Test
    fun `the keys land in the agent section`() {
        val document = DroshSettingsToml.toDocument(
            DroshSettings.DEFAULT.copy(
                agent = DroshSettings.DEFAULT.agent.copy(webSearch = false, effort = AgentEffort.LOW),
            ),
        )

        assertFalse(document.getBoolean("agent", "web_search", true))
        assertEquals("low", document.getString("agent", "effort", ""))
    }

    @Test
    fun `a file written before these keys existed still loads`() {
        val older = TomlDocument().apply {
            putString("appearance", "theme_mode", "dark")
        }

        val read = DroshSettingsToml.fromDocument(older)

        assertTrue(read.agent.webSearch)
        assertEquals(AgentEffort.DEFAULT, read.agent.effort)
    }

    @Test
    fun `an unreadable effort falls back rather than being stored verbatim`() {
        val hand = TomlDocument().apply {
            putString("agent", "effort", "turbo")
        }

        assertEquals(AgentEffort.MEDIUM, DroshSettingsToml.fromDocument(hand).agent.effort)
    }

    @Test
    fun `effort is case insensitive, like every other string in the file`() {
        val hand = TomlDocument().apply {
            putString("agent", "effort", "High")
        }

        assertEquals(AgentEffort.HIGH, DroshSettingsToml.fromDocument(hand).agent.effort)
    }

    @Test
    fun `the default is medium, because that is the one every current model takes`() {
        assertEquals(AgentEffort.MEDIUM, DroshSettings.DEFAULT.agent.effort)
        assertEquals("medium", AgentEffort.DEFAULT)
    }
}
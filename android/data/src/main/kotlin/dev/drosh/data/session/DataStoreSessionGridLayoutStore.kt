package dev.drosh.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.drosh.data.di.ApplicationScope
import dev.drosh.data.local.irisShellDataStore
import dev.drosh.domain.session.SessionGridLayout
import dev.drosh.domain.session.SessionGridLayoutStore
import dev.drosh.domain.session.SessionGroupLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The session grid's arrangement, in the shell preferences DataStore.
 *
 * A set of ids and two encoded lists rather than Room columns: the arrangement
 * refers to sessions that may not have a row yet, and none of it is part of a
 * session's identity.
 */
@Singleton
class DataStoreSessionGridLayoutStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope appScope: CoroutineScope,
) : SessionGridLayoutStore {

    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    private val _layout = MutableStateFlow<SessionGridLayout?>(null)
    override val layout: StateFlow<SessionGridLayout?> = _layout.asStateFlow()

    init {
        // The flow follows the file, so an arrangement edited by another
        // process is not overwritten by this one's stale copy.
        dataStore.data
            .map { prefs -> prefs.toLayout() }
            .distinctUntilChanged()
            .onEach { _layout.value = it }
            .launchIn(appScope)
    }

    override suspend fun save(layout: SessionGridLayout) {
        _layout.value = layout
        dataStore.edit { prefs ->
            if (layout.pinnedIds.isEmpty()) prefs.remove(KEY_PINNED_IDS)
            else prefs[KEY_PINNED_IDS] = layout.pinnedIds
            if (layout.orderedIds.isEmpty()) prefs.remove(KEY_ORDER)
            else prefs[KEY_ORDER] = layout.orderedIds.joinToString(ID_SEPARATOR)
            val encoded = SessionGridLayoutCodec.encodeGroups(layout.groups)
            if (encoded.isEmpty()) prefs.remove(KEY_GROUPS) else prefs[KEY_GROUPS] = encoded
        }
    }

    private fun Preferences.toLayout(): SessionGridLayout = SessionGridLayout(
        // The key is a set, so the stored order of pins is the file's, not the
        // user's; pins only sort a card to the front of its section.
        pinnedIds = this[KEY_PINNED_IDS] ?: emptySet(),
        orderedIds = SessionGridLayoutCodec.decodeOrder(this[KEY_ORDER].orEmpty()),
        groups = SessionGridLayoutCodec.decodeGroups(this[KEY_GROUPS].orEmpty()),
    )

    private companion object {
        val KEY_PINNED_IDS = stringSetPreferencesKey("session_grid_pinned_ids")
        val KEY_ORDER = stringPreferencesKey("session_grid_order")
        val KEY_GROUPS = stringPreferencesKey("session_grid_groups")
        const val ID_SEPARATOR = "\u001f"
    }
}

/**
 * The two encoded lists the store keeps.
 *
 * Unit separators (0x1F) rather than commas: a session id is a UUID today, but
 * the encoder should not depend on that, and a separator no id can contain is
 * the whole of the guarantee.
 */
internal object SessionGridLayoutCodec {

    private const val ID_SEPARATOR = "\u001f"
    private const val GROUP_SEPARATOR = "\u001e"

    /**
     * Separates a group's member list from its name.
     *
     * A group written by an older build has no name and no separator, so the
     * decoder treats a missing field as "unnamed" rather than as a member.
     */
    private const val NAME_SEPARATOR = "\u001d"

    /** Ids joined in their kept order; blank ids and duplicates are dropped. */
    fun encodeOrder(ids: List<String>): String =
        ids.filter { it.isNotBlank() }.distinct().joinToString(ID_SEPARATOR)

    fun decodeOrder(encoded: String): List<String> =
        encoded.split(ID_SEPARATOR)
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * Groups joined into one string. A group with fewer than two members is not
     * a group, and is dropped here rather than restored as a card that can never
     * be opened.
     */
    fun encodeGroups(groups: List<SessionGroupLayout>): String =
        groups
            .map { group -> group.copy(memberIds = group.memberIds.filter { it.isNotBlank() }.distinct()) }
            .filter { it.memberIds.size >= 2 }
            .joinToString(GROUP_SEPARATOR) { group ->
                val members = group.memberIds.joinToString(ID_SEPARATOR)
                val name = sanitizeName(group.name)
                if (name.isEmpty()) members else members + NAME_SEPARATOR + name
            }

    fun decodeGroups(encoded: String): List<SessionGroupLayout> =
        encoded.split(GROUP_SEPARATOR)
            .mapNotNull { entry ->
                if (entry.isBlank()) return@mapNotNull null
                val nameAt = entry.indexOf(NAME_SEPARATOR)
                val memberPart = if (nameAt >= 0) entry.substring(0, nameAt) else entry
                val name = if (nameAt >= 0) entry.substring(nameAt + 1, entry.length) else ""
                val members = memberPart.split(ID_SEPARATOR)
                    .filter { it.isNotBlank() }
                    .distinct()
                if (members.size < 2) null else SessionGroupLayout(members, name)
            }

    /** A name cannot carry a separator, or it would split its own record. */
    private fun sanitizeName(name: String): String =
        name.replace(ID_SEPARATOR, " ")
            .replace(GROUP_SEPARATOR, " ")
            .replace(NAME_SEPARATOR, " ")
            .trim()
            .take(MAX_GROUP_NAME_LENGTH)

    private const val MAX_GROUP_NAME_LENGTH = 60
}

package com.sider.lantern

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.writingDataStore by preferencesDataStore("writing_preferences")

internal enum class WritingSetting(val label: Int, key: String) {
    Brackets(R.string.complete_brackets, "complete_brackets"),
    Position(R.string.restore_position, "restore_position"),
    SpellCheck(R.string.spell_check, "spell_check"),
    Count(R.string.show_count, "show_count");

    val preference = booleanPreferencesKey(key)
}

internal data class WritingOptions(val disabled: Set<WritingSetting> = emptySet()) {
    operator fun get(setting: WritingSetting) = setting !in disabled
}

internal data class SavedEditorPosition(val caret: Int, val scroll: Int) {
    fun selectionIn(body: String): EditorPosition = graphemeOffset(body, caret).let { EditorPosition(it, it) }
}

internal class WritingPreferences(private val store: DataStore<Preferences>) {
    private val categoryKey = stringPreferencesKey("toolbar_category")
    private val data = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }
    val options = data.map { values ->
        WritingOptions(WritingSetting.entries.filterTo(mutableSetOf()) { values[it.preference] == false })
    }.distinctUntilChanged()
    val category = data.map { values ->
        WritingCategory.entries.firstOrNull { it.name == values[categoryKey] } ?: WritingCategory.Symbols
    }.distinctUntilChanged()
    val defaultExportTab = data.map { it[stringPreferencesKey("export_tab")]?.toIntOrNull()?.coerceIn(0, 2) ?: 0 }.distinctUntilChanged()
    val appearance = data.map { it[stringPreferencesKey("appearance")] ?: "system" }.distinctUntilChanged()

    val customization = data.map { values ->
        Customization(
            lantern = values[stringPreferencesKey("palette")] == "lantern",
            showRecent = values[stringPreferencesKey("show_recent")] != "false",
            left = SwipeAction.from(values[stringPreferencesKey("swipe_left")]),
            right = SwipeAction.from(values[stringPreferencesKey("swipe_right")]),
            font = EditorFont.from(values[stringPreferencesKey("editor_font")]),
            textSize = values[stringPreferencesKey("editor_size")]?.toIntOrNull()?.coerceIn(14, 26) ?: 17,
            lineGap = values[stringPreferencesKey("editor_gap")]?.toIntOrNull()?.coerceIn(0, 12) ?: 4,
            margin = values[stringPreferencesKey("editor_margin")]?.toIntOrNull()?.coerceIn(12, 40) ?: 20,
            toolbarExpanded = values[stringPreferencesKey("toolbar_expanded")] != "false",
            retention = TrashRetention.parse(values[stringPreferencesKey("trash_retention")]),
        )
    }.distinctUntilChanged()

    suspend fun setRetention(days: Int?, now: Long = System.currentTimeMillis()) {
        require(days == null || days in listOf(7, 30, 90))
        store.edit { values ->
            val key = stringPreferencesKey("trash_retention")
            val current = TrashRetention.parse(values[key])
            if (days != current.days) {
                val shortening = days != null && (current.days == null || days < current.days)
                val protection = if (shortening) now + DAY_MILLIS else current.protectedUntil
                values[key] = "${days ?: 0}:$protection"
            }
        }
    }

    suspend fun change(setting: WritingSetting, enabled: Boolean) {
        store.edit { it[setting.preference] = enabled }
    }

    suspend fun select(category: WritingCategory) {
        store.edit { it[categoryKey] = category.name }
    }

    suspend fun backup(): Map<String, String> {
        val values = data.first()
        return buildMap {
            WritingSetting.entries.forEach { put(it.preference.name, (values[it.preference] ?: true).toString()) }
            put(categoryKey.name, values[categoryKey] ?: WritingCategory.Symbols.name)
            backedUpKeys.forEach { key ->
                values[stringPreferencesKey(key)]?.let { put(key, it) }
            }
        }
    }

    suspend fun restore(settings: Map<String, String>) {
        store.edit { values ->
            WritingSetting.entries.forEach { setting ->
                settings[setting.preference.name]?.toBooleanStrictOrNull()?.let { values[setting.preference] = it }
            }
            settings[categoryKey.name]?.takeIf { value -> WritingCategory.entries.any { it.name == value } }
                ?.let { values[categoryKey] = it }
            settings.filterKeys { it in backedUpKeys }.forEach { (key, value) ->
                values[stringPreferencesKey(key)] = if (key == "trash_retention") {
                    val current = TrashRetention.parse(value)
                    "${current.days ?: 0}:${System.currentTimeMillis() + DAY_MILLIS}"
                } else value
            }
        }
    }

    suspend fun exportOption(key: String): String? = data.first()[stringPreferencesKey(key)]

    suspend fun setExportOption(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun loadPosition(id: String): SavedEditorPosition? {
        val values = data.first()
        if (values[WritingSetting.Position.preference] == false) return null
        val encoded = values[positionKey(id)] ?: return null
        val caret = encoded.substringBefore(':').toIntOrNull() ?: return null
        val scroll = encoded.substringAfter(':', "").toIntOrNull() ?: return null
        return SavedEditorPosition(caret, scroll)
    }

    suspend fun savePosition(id: String, position: SavedEditorPosition) {
        store.edit {
            if (it[WritingSetting.Position.preference] != false) {
                it[positionKey(id)] = "${position.caret}:${position.scroll}"
            }
        }
    }

    private fun positionKey(id: String) = stringPreferencesKey("position_$id")
}

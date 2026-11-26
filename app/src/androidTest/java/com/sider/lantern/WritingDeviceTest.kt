package com.sider.lantern

import android.content.Context
import android.os.Build
import android.view.inputmethod.EditorInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WritingDeviceTest {
    @Test fun paletteDefaultPreservesExistingAndBackedUpChoices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "palette-${UUID.randomUUID()}.preferences_pb")
        val job = Job()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            val fresh = WritingPreferences(store, defaultLantern = true)
            val existing = WritingPreferences(store)
            assertTrue(fresh.customization.first().lantern)
            assertFalse(existing.customization.first().lantern)
            assertEquals("lantern", fresh.backup()["palette"])
            fresh.restore(mapOf("palette" to "dynamic"))
            assertFalse(fresh.customization.first().lantern)
            fresh.restore(mapOf("palette" to "lantern"))
            assertTrue(fresh.customization.first().lantern)
            fresh.restore(mapOf("appearance" to "system"))
            assertFalse(fresh.customization.first().lantern)
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }

    @Test fun nativeCompletionDistinguishesCompositionAndKeepsSingleStepHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val session = EditorSession(Document.create("", ""))
                val view = ProbeEditText(activity, session, measureEdits = false)
                activity.setContentView(view)
                view.requestFocus()
                val attrs = EditorInfo()
                val connection = checkNotNull(view.onCreateInputConnection(attrs))
                val editable = view.text
                view.completeBrackets = true

                assertTrue(connection.commitText("「", 1))
                assertEquals("「」", view.text.toString())
                assertEquals(1, view.selectionStart)
                assertTrue(view.onTextContextMenuItem(android.R.id.undo))
                assertEquals("", view.text.toString())
                assertTrue(view.onTextContextMenuItem(android.R.id.redo))
                assertEquals("「」", view.text.toString())

                view.onTextContextMenuItem(android.R.id.undo)
                assertTrue(connection.setComposingText("「", 1))
                view.observe()
                assertNotNull(session.composition)
                assertEquals("", session.committed.document.body)
                assertTrue(connection.commitText("「", 1))
                view.observe()
                assertEquals("「", view.text.toString())
                assertNull(session.composition)

                view.onTextContextMenuItem(android.R.id.undo)
                editable.insert(0, "「") // Programmatic updates take the same Editable path as paste.
                view.observe()
                assertEquals("「", view.text.toString())
                view.onTextContextMenuItem(android.R.id.undo)
                editable.insert(0, "」")
                view.setSelection(0)
                view.observe()
                assertEquals("」", view.text.toString())
                if (Build.VERSION.SDK_INT >= 33) assertTrue(connection.commitText("「", 1, null))
                else assertTrue(connection.commitText("「", 1))
                assertEquals("「」", view.text.toString())
                assertEquals(1, view.selectionStart)

                view.completeBrackets = false
                assertTrue(connection.commitText("『", 1))
                view.observe()
                assertEquals("「『」", view.text.toString())
                assertTrue(view.isSuggestionsEnabled)
                view.setSpellCheck(false)
                assertFalse(view.isSuggestionsEnabled)
                val withSpellCheckOff = EditorInfo()
                assertNotNull(view.onCreateInputConnection(withSpellCheckOff))
                assertEquals(attrs.inputType, withSpellCheckOff.inputType)
                assertSame(editable, view.text)
            }
        }
    }

    @Test fun datastoreReloadsSettingsCategoryAndEachDraftPositionIndependently() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "writing-${UUID.randomUUID()}.preferences_pb")
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        suspend fun withPreferences(block: suspend (WritingPreferences) -> Unit) {
            val job = Job()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            try { block(WritingPreferences(store)) } finally { job.cancelAndJoin() }
        }
        try {
            withPreferences { prefs ->
                assertEquals(WritingOptions(), prefs.options.first())
                assertEquals(WritingCategory.Symbols, prefs.category.first())
                prefs.savePosition(firstId, SavedEditorPosition(4, 200))
                prefs.savePosition(secondId, SavedEditorPosition(8, 24))
                prefs.select(WritingCategory.Editing)
                prefs.change(WritingSetting.Brackets, false)
                prefs.change(WritingSetting.SpellCheck, false)
                prefs.change(WritingSetting.Count, false)
            }
            withPreferences { prefs ->
                assertEquals(setOf(WritingSetting.Brackets, WritingSetting.SpellCheck, WritingSetting.Count), prefs.options.first().disabled)
                assertEquals(WritingCategory.Editing, prefs.category.first())
                assertEquals(SavedEditorPosition(4, 200), prefs.loadPosition(firstId))
                assertEquals(SavedEditorPosition(8, 24), prefs.loadPosition(secondId))
                prefs.change(WritingSetting.Position, false)
                assertNull(prefs.loadPosition(firstId))
                prefs.savePosition(firstId, SavedEditorPosition(9, 0))
                prefs.change(WritingSetting.Position, true)
                assertEquals(SavedEditorPosition(4, 200), prefs.loadPosition(firstId))
            }
        } finally {
            file.delete()
        }
    }
}

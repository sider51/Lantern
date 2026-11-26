package com.sider.lantern

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveExportDeviceTest {
    @Test fun landscapePlacesImageSettingsBesideThePreview() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanternImageReview") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val automation = instrument.uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            var wide = false
            withTimeout(8_000) {
                while (!wide) {
                    scenario.onActivity { activity ->
                        val config = activity.resources.configuration
                        wide = config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 720
                    }
                    if (!wide) delay(100)
                }
            }
            val draft = visualReviewDraft()
            lateinit var labels: List<String>
            scenario.onActivity { activity ->
                labels = listOf(R.string.image_tab, R.string.image_settings, R.string.page_preview, R.string.paper)
                    .map(activity::getString)
                val model = ViewModelProvider(activity)[LanternModel::class.java]
                activity.setContent {
                    LanternTheme(lantern = true) {
                        ExportDestination(model, draft.id, onBack = {}, initial = draft)
                    }
                }
            }
            suspend fun node(label: String): AccessibilityNodeInfo = withTimeout(8_000) {
                var result: AccessibilityNodeInfo? = null
                while (result == null) {
                    result = find(automation.rootInActiveWindow, label)
                    if (result == null) delay(100)
                }
                result
            }
            fun click(start: AccessibilityNodeInfo) {
                var target: AccessibilityNodeInfo? = start
                while (target != null && !target.isClickable) target = target.parent
                assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            }
            click(node(labels[0]))
            click(node(labels[1]))
            val preview = Rect()
            val paper = Rect()
            repeat(10) {
                node(labels[2]).getBoundsInScreen(preview)
                node(labels[3]).getBoundsInScreen(paper)
                if (paper.left < preview.right) delay(120)
            }
            val context = instrument.targetContext
            val bitmap = automation.takeScreenshot()
            try {
                val directory = File(context.cacheDir, "ux-image-review").apply { mkdirs() }
                File(directory, "wide-image-settings.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            assertTrue("Settings ($paper) should start to the right of the preview ($preview)", paper.left >= preview.right)
        }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}

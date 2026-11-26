package com.sider.lantern

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageReviewDeviceTest {
    @Test fun renderLegacyAndRecommendedPngs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanternImageReview") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ux-image-review").apply { mkdirs() }
        val draft = visualReviewDraft()
        val samples = mapOf(
            "previous-vertical.png" to ImageOptions(),
            "recommended-vertical.png" to ImageOptions.recommended(),
            "recommended-horizontal.png" to ImageOptions.recommended(vertical = false),
        )
        for ((name, options) in samples) {
            val renderer = imageRenderer(context, draft, options)
            val pages = renderer.pages()
            assertEquals(draft.body, pages.joinToString("") { draft.body.substring(it.start, it.end) })
            assertTrue(pages.zipWithNext().all { (first, second) -> first.end == second.start })
            val bitmap = renderer.render(pages.first())
            try {
                assertEquals(options.width, bitmap.width)
                assertEquals(options.height, bitmap.height)
                assertEquals(options.background, bitmap.getPixel(0, 0))
                File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}

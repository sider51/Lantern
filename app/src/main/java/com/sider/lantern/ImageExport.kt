package com.sider.lantern

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
internal data class ImageOptions(
    val vertical: Boolean = true,
    val size: Int = 40,
    val width: Int = 728,
    val height: Int = 976,
    val left: Int = 64,
    val right: Int = 64,
    val top: Int = 64,
    val bottom: Int = 64,
    val letterSpacing: Int = 0,
    val lineSpacing: Int = 0,
    val background: Int = Color.WHITE,
    val foreground: Int = Color.BLACK,
    val font: String = "default",
    val customFont: String? = null,
    val title: Boolean = false,
    val author: String = "",
    val pageNumber: Boolean = false,
    val jpeg: Boolean = false,
    val watermark: String = "",
    val watermarkImage: String? = null,
    val watermarkPosition: Int = 3,
    val watermarkSize: Int = 18,
    val watermarkOpacity: Int = 25,
) {
    companion object {
        // The constructor defaults remain the serialization defaults for existing presets.
        fun recommended(vertical: Boolean = true) = ImageOptions(
            vertical = vertical, width = 1080, height = 1440, size = 39,
            left = 96, right = 96, top = 96, bottom = 96, lineSpacing = if (vertical) 60 else 38,
            letterSpacing = if (vertical) 3 else 0, background = 0xFFFAF8F3.toInt(),
            foreground = 0xFF302B25.toInt(), font = "noto_serif",
        )
    }

    fun reoriented(vertical: Boolean) = if (this == recommended(this.vertical)) recommended(vertical) else copy(vertical = vertical)

    fun validated() = apply {
        require(width in 256..3000 && height in 256..3000 && size in 12..160)
        require(listOf(left, top, right, bottom).all { it in 0..1400 })
        require(width - left - right >= size * 2 && height - top - bottom >= size * 2)
        require(letterSpacing in 0..40 && lineSpacing in 0..80)
        require(watermarkPosition in 0..3 && watermarkSize in 5..50 && watermarkOpacity in 0..100)
        require(author.isWellFormed() && watermark.isWellFormed())
    }
}

@Serializable
internal data class ImagePreset(val name: String, val options: ImageOptions)

internal class ImageExportFailure(val label: Int) : IllegalArgumentException()

private fun asset(context: Context, name: String): File {
    require(Regex("[a-f0-9]{64}\\.(font|mark)").matches(name))
    return File(File(context.filesDir, "export-assets"), name)
}

internal suspend fun importImageAsset(context: Context, uri: Uri, font: Boolean): String = withContext(Dispatchers.IO) {
    val directory = File(context.filesDir, "export-assets")
    check(directory.isDirectory || directory.mkdirs())
    val staged = File.createTempFile("incoming-", ".tmp", directory)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val limit = (if (font) 64 else 20) * 1024 * 1024
        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            staged.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    total += size
                    if (total > limit) throw ImageExportFailure(R.string.file_too_large)
                    digest.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                }
                output.fd.sync()
            }
        }
        if (font) {
            if (Typeface.Builder(staged).build() == null) throw ImageExportFailure(R.string.invalid_font)
        }
        else readWatermark(staged).recycle()
        val name = digest.digest().joinToString("") { "%02x".format(it) } + if (font) ".font" else ".mark"
        Files.move(staged.toPath(), asset(context, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        name
    } finally {
        staged.delete()
    }
}

private fun readWatermark(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    val scale = maxOf(info.size.width, info.size.height).toFloat() / 1024
    if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1),
        (info.size.height / scale).toInt().coerceAtLeast(1))
}

internal fun imageRenderer(context: Context, document: Document, options: ImageOptions): PageRenderer {
    val font = when (options.font) {
        "serif" -> Typeface.SERIF
        "custom" -> options.customFont?.let { asset(context, it) }?.takeIf(File::isFile)?.let(Typeface::createFromFile)
            ?: throw ImageExportFailure(R.string.pick_font_again)
        else -> EditorFont.entries.firstOrNull { it.imageKey == options.font }?.file
            ?.let { ResourcesCompat.getFont(context, it) } ?: Typeface.DEFAULT
    }
    val watermark = options.watermarkImage?.let { asset(context, it) }?.takeIf(File::isFile)?.let(::readWatermark)
    if (options.watermarkImage != null && watermark == null) throw ImageExportFailure(R.string.pick_watermark_again)
    return PageRenderer(document, options.vertical, font, options, watermark)
}

internal suspend fun outputImages(context: Context, plan: ImagePlan, share: Boolean): ArrayList<Uri> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val imageType = if (plan.jpeg) "image/jpeg" else "image/png"
    val extension = if (plan.jpeg) "jpg" else "png"
    val format = if (plan.jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
    val token = UUID.randomUUID().toString()
    val directory = File(context.cacheDir, "exports/$token")
    val output = arrayListOf<Uri>()
    try {
        if (share) check(directory.mkdirs())
        for ((index, page) in plan.pages.withIndex()) {
            val name = "Lantern-${token.take(8)}-${(index + 1).toString().padStart(3, '0')}.$extension"
            val bitmap = plan.renderer.render(page, index + 1)
            try {
                if (share) {
                    val file = File(directory, name)
                    file.outputStream().use { check(bitmap.compress(format, 94, it)) }
                    output += FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, imageType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lantern")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                    output += uri
                    checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(format, 94, it)) }
                }
            } finally { bitmap.recycle() }
        }
        if (!share) {
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            output.forEach { check(resolver.update(it, ready, null, null) == 1) }
        }
        output
    } catch (failure: Exception) {
        if (share) directory.deleteRecursively()
        else output.forEach { runCatching { resolver.delete(it, null, null) } }
        throw failure
    }
}

internal data class ImagePlan(val renderer: PageRenderer, val pages: List<PageSlice>, val jpeg: Boolean)

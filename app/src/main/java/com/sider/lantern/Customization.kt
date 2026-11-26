package com.sider.lantern

internal val backedUpKeys = setOf(
    "text_export", "image_presets", "image_export", "appearance", "palette", "show_recent",
    "swipe_left", "swipe_right", "editor_font", "editor_size", "editor_gap", "editor_margin",
    "toolbar_expanded", "trash_retention", "export_tab",
)

internal enum class EditorFont(val label: Int, val file: Int? = null, val imageKey: String = "default") {
    System(R.string.system_font),
    Sans(R.string.noto_sans, R.font.noto_sans_jp, "noto_sans"),
    Serif(R.string.noto_serif, R.font.noto_serif_jp, "noto_serif"),
    ZenGothic(R.string.zen_gothic, R.font.zen_kaku_gothic_new, "zen_gothic"),
    ZenMincho(R.string.zen_mincho, R.font.zen_old_mincho, "zen_mincho");

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: System
    }
}

internal enum class SwipeAction(val label: Int, val icon: Int) {
    None(R.string.no_action, R.drawable.ui_close),
    Move(R.string.move, R.drawable.ui_folder),
    Duplicate(R.string.duplicate, R.drawable.ui_copy),
    Rename(R.string.rename, R.drawable.ui_italic),
    Trash(R.string.move_to_trash, R.drawable.ui_delete);

    fun forFolder(folder: Boolean) = when {
        folder && this == Duplicate -> None
        else -> this
    }

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: None
    }
}

internal const val DAY_MILLIS = 86_400_000L
internal data class TrashRetention(val days: Int? = 30, val protectedUntil: Long = 0) {
    fun expires(removedAt: Long, now: Long): Boolean =
        days?.let { now >= protectedUntil && removedAt <= now - it * DAY_MILLIS } ?: false

    companion object {
        fun parse(value: String?): TrashRetention {
            val period = value?.substringBefore(':')?.toIntOrNull()
            return TrashRetention(
                days = if (period == 0) null else period?.takeIf { it in listOf(7, 30, 90) } ?: 30,
                protectedUntil = value?.substringAfter(':', "")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            )
        }
    }
}

internal data class Customization(
    val lantern: Boolean = false,
    val showRecent: Boolean = true,
    val left: SwipeAction = SwipeAction.None,
    val right: SwipeAction = SwipeAction.None,
    val font: EditorFont = EditorFont.System,
    val textSize: Int = 17,
    val lineGap: Int = 4,
    val margin: Int = 20,
    val toolbarExpanded: Boolean = true,
    val retention: TrashRetention = TrashRetention(),
)

internal data class LibrarySelection(val folders: Set<String> = emptySet(), val drafts: Set<String> = emptySet()) {
    val size get() = folders.size + drafts.size
}

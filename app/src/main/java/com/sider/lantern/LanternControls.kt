package com.sider.lantern

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun LanternIcon(
    @DrawableRes icon: Int, description: String? = null, size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) = Icon(painterResource(icon), description, Modifier.size(size), tint = tint)

@Composable
internal fun LanternIconButton(@DrawableRes icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier = Modifier.size(48.dp), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        LanternIcon(icon, label)
    }
}

@Composable
internal fun LanternBack(enabled: Boolean = true, onClick: () -> Unit) =
    LanternIconButton(R.drawable.ui_back, stringResource(R.string.back), enabled, onClick)

@Composable
internal fun LanternSection(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier.semantics { heading() }.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LanternActionRow(
    @DrawableRes icon: Int,
    label: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LanternIcon(icon, tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface.copy(alpha = if (enabled) 1f else .38f))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        LanternIcon(R.drawable.ui_next, size = 20.dp, tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
    }
}

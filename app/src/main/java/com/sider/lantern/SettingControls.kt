package com.sider.lantern

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun IntSlider(
    label: String, value: Int, bounds: IntRange, @StringRes unit: Int? = null,
    exact: Boolean = false, onPreview: (Int) -> Unit = {}, onCommit: (Int) -> Unit,
) {
    var position by remember(label, value, bounds) { mutableIntStateOf(value) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val display = if (unit == null) position.toString() else stringResource(unit, position)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (exact) TextButton({ editing = true }) { Text(display) }
            else Text(display, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = position.coerceIn(bounds).toFloat(), enabled = bounds.first < bounds.last,
            valueRange = bounds.first.toFloat()..maxOf(bounds.first + 1, bounds.last).toFloat(),
            modifier = Modifier.semantics { contentDescription = label },
            onValueChange = {
                position = it.roundToInt().coerceIn(bounds)
                onPreview(position)
            },
            onValueChangeFinished = { if (position != value) onCommit(position) },
        )
    }
    if (editing) {
        var text by rememberSaveable { mutableStateOf(position.toString()) }
        val number = text.toIntOrNull()
        val valid = number != null && number in bounds
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(label) }, text = {
            OutlinedTextField(text, { text = it.take(7) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.numeric_range, bounds.first, bounds.last)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid)
        }, confirmButton = {
            TextButton({ editing = false; checkNotNull(number).let { position = it; onPreview(it); onCommit(it) } },
                enabled = valid) { Text(stringResource(R.string.apply)) }
        }, dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun <T> SegmentSetting(label: String, value: T, choices: List<Pair<T, String>>, change: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, (option, title) ->
                SegmentedButton(selected = value == option, onClick = { change(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size)) { Text(title) }
            }
        }
    }
}

internal fun fontFamily(font: EditorFont) = font.file?.let { FontFamily(Font(it)) } ?: FontFamily.Default

@Composable
internal fun FontSpecimen(family: FontFamily) {
    Text(stringResource(R.string.font_specimen), maxLines = 2,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family, lineHeight = 23.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ColorPickerSetting(label: String, value: Int, change: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val formatted = colorHex(value)
    ListItem(modifier = Modifier.clickable { open = true }, trailingContent = { Swatch(Color(value), false) },
        supportingContent = { Text(formatted) }) { Text(label) }
    if (!open) return
    var rgb by rememberSaveable { mutableIntStateOf(value) }
    var hex by rememberSaveable { mutableStateOf(formatted) }
    val validHex = Regex("#[a-fA-F0-9]{6}").matches(hex)
    fun select(color: Int) {
        rgb = color
        hex = colorHex(color)
    }
    val colors = listOf(0xFFFAF8F3, 0xFFF0E9DF, 0xFFEED6AE, 0xFF302B25, 0xFF161A24, 0xFFFFFFFF)
    AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { swatch ->
                        val color = swatch.toInt()
                        IconButton(onClick = { select(color) }, modifier = Modifier.size(48.dp)
                            .semantics { contentDescription = colorHex(color) }) {
                            Swatch(Color(color), rgb == color)
                        }
                    }
                }
            }
            val red = android.graphics.Color.red(rgb)
            val green = android.graphics.Color.green(rgb)
            val blue = android.graphics.Color.blue(rgb)
            IntSlider(stringResource(R.string.color_red), red, 0..255,
                onPreview = { select(android.graphics.Color.rgb(it, green, blue)) }, onCommit = {})
            IntSlider(stringResource(R.string.color_green), green, 0..255,
                onPreview = { select(android.graphics.Color.rgb(red, it, blue)) }, onCommit = {})
            IntSlider(stringResource(R.string.color_blue), blue, 0..255,
                onPreview = { select(android.graphics.Color.rgb(red, green, it)) }, onCommit = {})
            OutlinedTextField(hex, { next ->
                if (next.length <= 7) {
                    hex = next
                    if (Regex("#[a-fA-F0-9]{6}").matches(next)) rgb = android.graphics.Color.parseColor(next)
                }
            }, singleLine = true, label = { Text(stringResource(R.string.hex_color)) },
                trailingIcon = { Swatch(Color(rgb), false) }, isError = !validHex)
        }
    }, confirmButton = {
        TextButton({ open = false; change(rgb) }, enabled = validHex) { Text(stringResource(R.string.apply)) }
    }, dismissButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun Swatch(color: Color, selected: Boolean) {
    Box(Modifier.size(28.dp).background(color, CircleShape).border(if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
}

private fun colorHex(value: Int) = "#%06X".format(value and 0xffffff)

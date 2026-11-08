package io.github.zailinn.flymefreeform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.zailinn.flymefreeform.R
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.window.MoreButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 跟随系统时「更多」底板与三点的颜色，与扇形 RadialItemColors 的取值一致。 */
private const val SYSTEM_LIGHT_PLATE = 0xFFF7F7F8.toInt()
private const val SYSTEM_DARK_PLATE = 0xFF1F1F1F.toInt()

/**
 * 「更多」按钮颜色：点开后用取色器自由选色，可恢复跟随系统。
 * 三个点不单独设置，按底板亮度自动取黑或白（见 [MoreButtonColors]），白色底板即为黑点。
 */
@Composable
internal fun MoreColorPreference(
    color: Int,
    enabled: Boolean,
    onColorChange: (Int) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val followSystem = color == ModulePreferences.DEFAULT_RADIAL_MORE_COLOR
    var showDialog by remember { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(R.string.radial_more_color_title),
        summary =
            if (followSystem) {
                stringResource(R.string.radial_more_color_follow_system)
            } else {
                stringResource(R.string.radial_more_color_custom_summary, colorText(color))
            },
        onClick = { showDialog = true },
        enabled = enabled,
        startAction = { PreferenceIcon(Icons.Rounded.Palette, enabled) },
        endActions = {
            MoreButtonSwatch(color = color, dark = dark, size = 28.dp, modifier = Modifier.padding(end = 8.dp))
        },
    )
    if (!showDialog) return
    // 每次打开从当前取值开始；跟随系统时从当前深浅色的默认底板开始调。
    var draft by remember {
        mutableStateOf(Color(if (followSystem) (if (dark) SYSTEM_DARK_PLATE else SYSTEM_LIGHT_PLATE) else color))
    }
    OverlayDialog(
        show = showDialog,
        title = stringResource(R.string.radial_more_color_title),
        summary = stringResource(R.string.radial_more_color_dialog_summary),
        onDismissRequest = { showDialog = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                MoreButtonSwatch(color = ModulePreferences.customRadialMoreColor(draft.toArgb()), dark = dark, size = 56.dp)
            }
            ColorPicker(color = draft, onColorChanged = { draft = it })
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.radial_more_color_follow_system),
                    onClick = {
                        onColorChange(ModulePreferences.DEFAULT_RADIAL_MORE_COLOR)
                        showDialog = false
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.radial_more_color_confirm),
                    onClick = {
                        onColorChange(ModulePreferences.customRadialMoreColor(draft.toArgb()))
                        showDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 按扇形里的画法画一个小号「更多」：底板加横排三点；浅色底板加一圈细边，避免与卡片底色融在一起。 */
@Composable
private fun MoreButtonSwatch(color: Int, dark: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val plate =
        if (color == ModulePreferences.DEFAULT_RADIAL_MORE_COLOR) {
            if (dark) SYSTEM_DARK_PLATE else SYSTEM_LIGHT_PLATE
        } else {
            color
        }
    val dot =
        if (color == ModulePreferences.DEFAULT_RADIAL_MORE_COLOR) {
            if (dark) MoreButtonColors.LIGHT_DOT else MoreButtonColors.DARK_DOT
        } else {
            MoreButtonColors.dotFor(color)
        }
    val outline = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        drawCircle(color = Color(plate), radius = radius)
        drawCircle(color = outline, radius = radius - 0.5.dp.toPx(), style = Stroke(width = 1.dp.toPx()))
        for (offset in -1..1) {
            drawCircle(
                color = Color(dot),
                radius = this.size.minDimension * 0.06f,
                center = Offset(center.x + offset * this.size.minDimension * 0.2f, center.y),
            )
        }
    }
}

private fun colorText(color: Int): String {
    val alpha = color ushr 24
    return if (alpha == 0xFF) "#%06X".format(color and 0xFFFFFF) else "#%08X".format(color)
}

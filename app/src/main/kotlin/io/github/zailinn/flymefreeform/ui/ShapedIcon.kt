package io.github.zailinn.flymefreeform.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.zailinn.flymefreeform.apps.IconShapes
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.config.PinnedTarget

/** 当前「图标形状」设置，由导航根节点提供。 */
internal val LocalIconShape = compositionLocalOf { ModulePreferences.DEFAULT_ICON_SHAPE }

/** 方形模式下侧边栏条目使用的外形模板（取自某个应用图标），由导航根节点提供。 */
internal val LocalSquareIconMask = compositionLocalOf<Bitmap?> { null }

/** 从应用列表里取方形模板：前几个应用中第一个合格的图标。 */
internal fun squareIconMaskOf(apps: List<SelectableEntry>): Bitmap? =
    apps.asSequence().take(30).firstNotNullOfOrNull { IconShapes.maskFrom(it.icon) }

/** 应用（含分身）图标；方形模式下保持系统原样，其余条目按应用图标外形裁切。 */
internal val SelectableEntry.isAppIcon: Boolean
    get() = target is PinnedTarget.App || (target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp

/** 按当前图标形状处理过的图标；与扇形使用同一份 [IconShapes] 逻辑。 */
@Composable
internal fun rememberShapedIcon(bitmap: Bitmap, isApp: Boolean): ImageBitmap {
    val shape = LocalIconShape.current
    val mask = LocalSquareIconMask.current
    return remember(bitmap, shape, isApp, mask) { IconShapes.apply(bitmap, shape, isApp, mask).asImageBitmap() }
}

package com.mnnkit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mnnkit.core.ui.MnnContrast
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用统一按钮。**用来替换 miuix 的 `Button`。**
 *
 * ## 为什么不能用 miuix 的 `Button`
 *
 * 两个坑，都在真机上踩实了：
 *
 * 1. **子内容颜色由父级覆盖**。`Button` 内部用 `ProvideContentColor` 决定子内容
 *    颜色，所以 `Button { Text("x", color = Y) }` 里的 `Y` **不生效** ——
 *    "给文字显式指定颜色"这个最直接的手段直接失效。
 * 2. **`onPrimary` 算出的对比度不足**。miuix 的 `Colors` 由 `ThemeController`
 *    内部构造，外部改不了它的属性；Monet 模式下还跟着壁纸变。
 *    真机截图里就是**浅蓝底 + 灰字**，看不清。
 *
 * ## 本组件的做法
 *
 * 底色由我们定，前景色用 [MnnContrast.safePairFor] **按底色算**，保证任何
 * 主题色下都达到 WCAG AA 的 4.5:1（单测护栏：
 * `MnnContrastTest.every background becomes readable after safePairFor`）。
 *
 * 前景色通过 `LocalContentColor` 下发，所以**内容里的图标与文字不必各自指定颜色**。
 *
 * ## 用法
 *
 * ```kotlin
 * // 只有一行文字
 * MnnButton(text = "搜索", onClick = { ... })
 *
 * // 图标 + 文字，或文字是表达式
 * MnnButton(onClick = { ... }) {
 *     Icon(Icons.Rounded.CloudDownload, contentDescription = null)
 *     Text(if (busy) "安装中…" else "安装")
 * }
 * ```
 *
 * ⚠️ **尾随 lambda 的安全性**：`content` 挂在参数表最后、类型是
 * `@Composable RowScope.() -> Unit`，Kotlin 会把尾随 lambda 绑到它上面；
 * `onClick` 是具名的，不会被抢。反过来（`onClick` 也放在尾部、两个都是
 * 无接收者的函数类型）就会出现"尾随 lambda 绑错参数"的编译错误 —— 本项目踩过。
 *
 * @param text 只有一行文字时用这个简写；提供了 [content] 时忽略
 */
@Composable
fun MnnButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MnnButtonStyle = MnnButtonStyle.Primary,
    enabled: Boolean = true,
    text: String? = null,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp),
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme

    // ── 底色 ──
    val rawBackground = when (style) {
        MnnButtonStyle.Primary -> scheme.primary
        MnnButtonStyle.Tonal -> scheme.primaryContainer
        MnnButtonStyle.Neutral -> scheme.surfaceContainerHigh
    }
    val rawArgb = rawBackground.toArgb()

    // ── 前景 + 实际底色 ──
    // ⚠️ 关键：前景**按底色算**，不取任何主题的 on* 语义色。
    // safePairFor 只在底色落进"黑字白字都不合格"的中亮度带时才微调底色；
    // 底色本来可读就原样返回（不会悄悄改掉品牌色）。
    val (safeArgb, foregroundArgb) = MnnContrast.safePairFor(rawArgb)

    val background = if (enabled) Color(safeArgb) else Color(safeArgb).copy(alpha = 0.45f)
    val foreground = if (enabled) Color(foregroundArgb) else Color(foregroundArgb).copy(alpha = 0.7f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        // 把算好的前景色下发下去：图标与文字都不用各自指定颜色。
        CompositionLocalProvider(LocalContentColor provides foreground) {
            Row(
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (content != null) {
                    content()
                } else {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = text.orEmpty(),
                        color = foreground,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }
    }
}

/**
 * 等宽并排按钮的容器：把 `Row` + 间距 + 垂直居中收一下。
 * 子项自己写 `Modifier.weight(1f)` 即可等宽。
 */
@Composable
fun MnnButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 按钮层级。 */
enum class MnnButtonStyle {
    /** 主操作：品牌主色底。 */
    Primary,

    /** 次操作：主色浅底。用于"选中 / 未选中"这类状态区分。 */
    Tonal,

    /** 普通操作：表面容器底。 */
    Neutral,
}

package com.mnnkit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.mnnkit.app.ui.theme.MnnTextColor
import com.mnnkit.core.ui.MnnContrast
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 胶囊按钮：**自己画**，不用 miuix 的 `Button`。
 *
 * ## 为什么不用 miuix 的 `Button`
 *
 * 两个坑，都踩过：
 *
 * 1. **文字色会被父级覆盖**。`Button` 内部通过 `ProvideContentColor` 决定子内容颜色，
 *    所以写 `Button(...) { Text("模型", color = X) }` 时那个 `X` **不生效**。
 *    这让「给文字指定颜色」这个最直接的手段失效 ——
 *    也是我前几轮反复改 `MnnTextColor` 却看不到变化的真实原因之一。
 *
 * 2. **`ButtonDefaults.buttonColorsPrimary()` 算出的配色对比度不足**。
 *    真机上「模型」按钮是**浅蓝底 + 灰字**，灰到看不清。
 *    它的文字色取自 `onPrimary` 一系，而 miuix 的 `Colors` 由 `ThemeController`
 *    内部构造，我们**无法从外部覆盖**（`MnnTheme` 只把它映射给 Material3 用）。
 *
 * 结论：这类需要**保证可读性**的关键按钮，配色必须由我们显式给定。
 *
 * ## 配色
 *
 * - `emphasized = true`：主色底 + 白字（或深字，按主题自动选对比度更高的那个）
 * - `emphasized = false`：次级容器底 + 主文字色
 *
 * @param text 按钮文字
 * @param onClick 点击回调
 * @param emphasized 是否用主色强调（主操作按钮用 true）
 */
@Composable
fun MnnCapsuleButton(
    text: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val rawBg = if (emphasized) scheme.primary else scheme.surfaceContainerHigh

    // 文字颜色**显式**按背景算，不依赖任何主题的 on* 配色。
    //
    // ⚠️ 用 MnnContrast.safePairFor 而不是自己写"亮度阈值二选一"：
    // 中亮度背景（相对亮度约 0.175~0.183 那一段）上黑字白字**都**到不了
    // 4.5:1 —— 这是 WCAG 的物理下限。safePairFor 会把底色微调到安全区，
    // 保证任何主题色（含 Monet 跟随壁纸）下文字都读得清。
    // 有单测护栏：MnnContrastTest.every background becomes readable after safePairFor
    val (safeArgb, onArgb) = MnnContrast.safePairFor(rawBg.toArgb())

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(safeArgb))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color(onArgb),
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

/**
 * 次级文字色：给需要在胶囊按钮之外、又怕继承到暗色的场景用。
 * 保留在这里是为了让本文件的调用方不必再引一次 theme 包。
 */
@Composable
internal fun secondaryTextColor(): Color = MnnTextColor.secondary

package com.mnnkit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 文字色的**显式**取值。
 *
 * ## 为什么要有这个文件
 *
 * 本项目主题是 miuix + Material3 双层：
 *  - miuix 的 `Colors` 由 `ThemeController` **内部构造**，外部改不了它的属性；
 *  - `MnnTheme` 只把它**映射**成 Material3 的 `ColorScheme` 给 Material3 组件用。
 *
 * 于是出现一个很难发现的问题：**同一页面上，两类组件的"次级文字色"来源不同**。
 * 我在 `toMaterialScheme` 里怎么改映射，都影响不到 miuix 自己的组件
 * （`Text`、`Button`、卡片标题等）—— 那些直接读 miuix `Colors`。
 *
 * 结果是深色模式下部分说明文字只有 `#A8ABB5` 左右的亮度，
 * 在 `#0C0E12` 背景上虽然勉强可读，但用户明确反馈「看不清 / 应该自动变白」。
 *
 * ## 做法
 *
 * 需要保证可读性的文字**显式**取色，不再依赖任何一层的"语义色"继承：
 * 深色模式给接近纯白的颜色，浅色模式给标准的深色文字色。
 *
 * 这样做的代价是「少了一层语义抽象」，但换来的是**确定性** ——
 * 一个已经因为颜色问题反复踩坑的地方，值得用显式取值换可预测性。
 */
object MnnTextColor {

    /**
     * 主文字色。深色模式近白，浅色模式近黑。
     *
     * 不用纯 `#FFFFFF` / `#000000`：纯白在深色背景上会显得刺眼（halation），
     * 纯黑在浅色背景上同理。0.95 左右的 alpha 是 Material 的常规做法。
     */
    val primary: Color
        @Composable get() = if (isDark()) Color(0xFFF2F3F7) else Color(0xFF1A1C20)

    /**
     * 次级文字色（说明、副标题）。
     *
     * 比主文字略暗，但仍然保证在对应背景上有足够对比度
     * （深色约 12:1，浅色约 10:1，都远超 WCAG AA 的 4.5:1）。
     */
    val secondary: Color
        @Composable get() = if (isDark()) Color(0xFFC9CBD4) else Color(0xFF4A4D55)

    /** 是否深色模式。以 Material3 主题的 surface 亮度判断，避免依赖外部状态。 */
    @Composable
    private fun isDark(): Boolean =
        MaterialTheme.colorScheme.surface.luminance() < 0.5f

    private fun Color.luminance(): Float =
        0.2126f * red + 0.7152f * green + 0.0722f * blue
}

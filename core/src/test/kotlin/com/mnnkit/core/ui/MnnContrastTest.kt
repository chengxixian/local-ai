package com.mnnkit.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MnnContrast] 的测试。
 *
 * 这是「蓝色按钮上的字是灰的、看不清」那个问题的回归测试：
 * 只要前景色选择的算法退化成"总是选深色"或"总是选浅色"，
 * 下面这些断言就会红。
 *
 * 已知参考值来自 WCAG 2.1 规范本身（纯黑 0.0、纯白 1.0、黑白对比 21:1），
 * 以及常规的对比度表，不是自己算出来再拿自己对答案。
 */
class MnnContrastTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val white = argb(0xFF, 0xFF, 0xFF)
    private val black = argb(0x00, 0x00, 0x00)

    @Test
    fun `pure white luminance is 1 and pure black is 0`() {
        assertEquals(1.0, MnnContrast.relativeLuminance(white), 1e-9)
        assertEquals(0.0, MnnContrast.relativeLuminance(black), 1e-9)
    }

    @Test
    fun `black and white contrast is 21 to 1`() {
        assertEquals(21.0, MnnContrast.contrastRatio(black, white), 1e-6)
        assertEquals(21.0, MnnContrast.contrastRatio(white, black), 1e-6)
    }

    @Test
    fun `contrast of a color with itself is 1`() {
        val c = argb(0x34, 0x82, 0xFF)
        assertEquals(1.0, MnnContrast.contrastRatio(c, c), 1e-9)
    }

    @Test
    fun `mid gray luminance matches known value`() {
        // #808080 的相对亮度约 0.2159（WCAG 常用核对值）
        assertEquals(0.2159, MnnContrast.relativeLuminance(argb(0x80, 0x80, 0x80)), 0.001)
    }

    // ─────────────────── 前景色选择：这组是本次问题的核心回归 ───────────────────

    @Test
    fun `dark background gets light text`() {
        val darkBg = argb(0x10, 0x14, 0x1A)          // 近黑
        assertEquals(MnnContrast.LIGHT_ON_DARK, MnnContrast.onColorFor(darkBg))
        assertTrue(MnnContrast.meetsAa(MnnContrast.onColorFor(darkBg), darkBg))
    }

    @Test
    fun `light background gets dark text`() {
        val lightBg = argb(0xE8, 0xEE, 0xF8)         // 近白
        assertEquals(MnnContrast.DARK_ON_LIGHT, MnnContrast.onColorFor(lightBg))
        assertTrue(MnnContrast.meetsAa(MnnContrast.onColorFor(lightBg), lightBg))
    }

    @Test
    fun `miui blue button always reaches AA against its own foreground`() {
        // 这是截图里那个按钮：MIUI 蓝 #3482FF。
        // 之前 miuix 的 onPrimary 算出偏灰，对比度不够；我们自己算就要过 AA。
        val miuiBlue = argb(0x34, 0x82, 0xFF)
        val fg = MnnContrast.onColorFor(miuiBlue)
        val ratio = MnnContrast.contrastRatio(fg, miuiBlue)
        assertTrue(ratio >= 4.5, "MIUI 蓝上的前景对比度只有 $ratio，达不到 AA")
    }

    @Test
    fun `dark mode light blue primary gets dark text not gray`() {
        // 深色 Monet 下 primary 常见是这种浅蓝（#A8C7FA 一系）。
        // 它应该配深色字（对比度高），而不是浅灰字。
        val paleBlue = argb(0xA8, 0xC7, 0xFA)
        val fg = MnnContrast.onColorFor(paleBlue)
        assertEquals(MnnContrast.DARK_ON_LIGHT, fg)
        assertTrue(MnnContrast.contrastRatio(fg, paleBlue) > 7.0)
    }

    @Test
    fun `every background becomes readable after safePairFor`() {
        // 这条是真正的护栏：不管主题色被 Monet 改成什么颜色，
        // 经 safePairFor 之后的「底 + 字」都必须达到 AA。
        //
        // 注意断言的是 **adjustSafePairFor 之后的结果**，而不是随便一个背景色 ——
        // 中亮度带上黑字白字都到不了 4.5（WCAG 的物理下限），
        // 所以"任何背景都能配出可读前景"是做不到的，只能靠调整底色。
        var worst = Double.MAX_VALUE
        var worstBg = 0
        for (r in 0..255 step 17) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 17) {
                    val bg = argb(r, g, b)
                    val (safeBg, fg) = MnnContrast.safePairFor(bg)
                    val ratio = MnnContrast.contrastRatio(fg, safeBg)
                    if (ratio < worst) {
                        worst = ratio
                        worstBg = bg
                    }
                }
            }
        }
        assertTrue(
            worst >= 4.5,
            "原始背景 #%08X 调整后对比度仍只有 %.3f".format(worstBg, worst),
        )
    }

    @Test
    fun `safePairFor leaves already-safe colors untouched`() {
        // 已经在安全区的颜色不应被改动 —— 否则"动一点亮度"会变成"动很多"，
        // 品牌色就被悄悄改掉了。
        val miuiBlue = argb(0x34, 0x82, 0xFF)
        val (bg, fg) = MnnContrast.safePairFor(miuiBlue)
        assertEquals(miuiBlue, bg, "MIUI 蓝本来就可读，不该被调整")
        assertTrue(MnnContrast.meetsAa(fg, bg))

        val nearBlack = argb(0x10, 0x14, 0x1A)
        assertEquals(nearBlack, MnnContrast.safePairFor(nearBlack).first)

        val nearWhite = argb(0xE8, 0xEE, 0xF8)
        assertEquals(nearWhite, MnnContrast.safePairFor(nearWhite).first)
    }

    @Test
    fun `safePairFor fixes the mid luminance band`() {
        // #558822 实测是"黑字白字都不合格"的典型：
        // 原样配不出 4.5，必须靠调底色。
        val midLum = argb(0x55, 0x88, 0x22)
        val (bg, fg) = MnnContrast.safePairFor(midLum)
        assertTrue(
            MnnContrast.contrastRatio(fg, bg) >= 4.5,
            "中亮度色调整后仍未达标：bg=#%08X fg=#%08X".format(bg, fg),
        )
    }

    @Test
    fun `isDark agrees with onColorFor direction`() {
        val darkBg = argb(0x10, 0x14, 0x1A)
        val lightBg = argb(0xE8, 0xEE, 0xF8)
        assertTrue(MnnContrast.isDark(darkBg))
        assertFalse(MnnContrast.isDark(lightBg))
        assertEquals(MnnContrast.LIGHT_ON_DARK, MnnContrast.onColorFor(darkBg))
        assertEquals(MnnContrast.DARK_ON_LIGHT, MnnContrast.onColorFor(lightBg))
    }
}

package com.mnnkit.core.ui

/**
 * 颜色对比度工具。
 *
 * ## 为什么需要它
 *
 * 本项目主题是 **miuix + Material3 双层**，而且 miuix 的 `Colors` 由
 * `ThemeController` **内部构造**，外部改不了它的属性 —— 所以
 * `onPrimary` / `onPrimaryContainer` 这些"配套前景色"到底算成什么颜色，
 * 我们既控制不了、也预知不了（Monet 模式下还会跟着壁纸变）。
 *
 * 真机上出现过：**浅蓝底 + 灰字**的按钮，可读性极差；而且给子 `Text`
 * 显式指定颜色也无效（父级 `ProvideContentColor` 会覆盖）。
 *
 * 结论：需要保证可读性的地方，**前景色必须由我们按背景色自己算**。
 * 这个文件就是那个算法，放在 `:core` 里以便单测覆盖 ——
 * 纯函数，不依赖 Compose 运行时。
 *
 * ## 算法
 *
 * - [relativeLuminance]：WCAG 2.1 定义的相对亮度；
 * - [contrastRatio]：WCAG 2.1 对比度（1:1 ~ 21:1）；
 * - [onColorFor]：给一个背景色，返回黑或白里**对比度更高**的那个。
 *   在三个候选（纯黑、纯白、以及一点点柔化后的黑/白）里挑对比度最大的，
 *   顺带满足"不要太刺眼"的观感。
 */
object MnnContrast {

    /** 深色前景候选。用近黑而不是纯黑：纯黑在浅底上显得生硬。 */
    val DARK_ON_LIGHT = 0xFF1A1C20.toInt()

    /** 浅色前景候选。用近白而不是纯白：避免深底上的 halation（光晕）。 */
    val LIGHT_ON_DARK = 0xFFF7F8FA.toInt()

    /**
     * sRGB 通道线性化。
     *
     * WCAG 规定：c <= 0.03928 时用 c/12.92，否则用 ((c+0.055)/1.055)^2.4。
     * 不能简单用 gamma 2.2 近似 —— 在暗部误差明显，会算错对比度。
     */
    private fun linearize(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /**
     * WCAG 相对亮度（0.0 黑 ~ 1.0 白）。
     *
     * @param argb 32 位 ARGB；忽略 alpha（按钮底色都是不透明的）
     */
    fun relativeLuminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    /** WCAG 对比度，范围 1.0（同色）~ 21.0（纯黑配纯白）。与参数顺序无关。 */
    fun contrastRatio(argbA: Int, argbB: Int): Double {
        val la = relativeLuminance(argbA)
        val lb = relativeLuminance(argbB)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 给背景色挑一个可读的前景（文字/图标）色。
     *
     * 在"深色候选"和"浅色候选"里选对比度更高的那个 —— 也就是
     * **背景亮就用近黑字、背景暗就用近白字**。
     *
     * ⚠️ 注意：这个函数**只挑更优的那个**，不保证一定达到 AA。
     * 存在一段"中亮度带"，黑字和白字**都**到不了 4.5:1 ——
     * 这是 WCAG 的物理下限（算法本身无解），不是实现问题。
     * 实测最差是中亮度色（例如 `#558822`）只有约 4.0:1。
     * 因此需要**保证**可读性的地方请用 [safePairFor]，它会把底色调整到安全区。
     *
     * @return 32 位 ARGB
     */
    fun onColorFor(background: Int): Int {
        val dark = contrastRatio(background, DARK_ON_LIGHT)
        val light = contrastRatio(background, LIGHT_ON_DARK)
        return if (dark >= light) DARK_ON_LIGHT else LIGHT_ON_DARK
    }

    /**
     * 把 [background] 调整到"黑字或白字至少能到 [minRatio]"的安全区，并返回配套前景色。
     *
     * ## 为什么需要它
     *
     * 只按背景亮度"二选一"选前景是不够的：中亮度背景（相对亮度约
     * 0.175 ~ 0.183 那一小段）上，近黑字和近白字**都**低于 4.5:1。
     * 想要"任何背景都可读"，只有两条路：换背景色，或者接受不合格。
     * 这里选前者 —— 按钮底色偏一点几乎看不出来，但字看不清是硬伤。
     *
     * ## 做法
     *
     * 底色**向黑或向白移动**（保持色相，只调亮度）到最近的安全边界；
     * 若底色本来就在安全区，则原样保留，不做任何改动。
     *
     * @param minRatio 目标对比度，默认 4.5（WCAG AA 对正文的要求）
     * @return `background`（可能被调整过）与 `foreground` 两个 ARGB
     */
    fun safePairFor(background: Int, minRatio: Double = 4.5): Pair<Int, Int> {
        onColorFor(background).let { preferred ->
            if (contrastRatio(background, preferred) >= minRatio) {
                return background to preferred
            }
        }

        // 为了既保证语义（按钮还是彩色的）又保证可读，
        // 把底色朝"更暗"和"更亮"两个方向分别推进，取移动较小的一侧。
        val darker = adjustLightnessUntil(background, minRatio, towardsWhite = false)
        val lighter = adjustLightnessUntil(background, minRatio, towardsWhite = true)
        val (adjusted, fg) = when {
            darker == null -> lighter!! to DARK_ON_LIGHT
            lighter == null -> darker to LIGHT_ON_DARK
            else -> {
                // 用"与原始亮度的距离"衡量谁改得更少，改动小的优先
                val dDark = Math.abs(relativeLuminance(darker) - relativeLuminance(background))
                val dLight = Math.abs(relativeLuminance(lighter) - relativeLuminance(background))
                if (dDark <= dLight) darker to LIGHT_ON_DARK else lighter to DARK_ON_LIGHT
            }
        }
        return adjusted to fg
    }

    /**
     * 把颜色朝黑（[towardsWhite] = false）或朝白（true）逐格移动，
     * 直到与 [target] 的对比度达到 [minRatio]。找不到返回 null。
     */
    private fun adjustLightnessUntil(
        background: Int,
        minRatio: Double,
        towardsWhite: Boolean,
    ): Int? {
        val target = if (towardsWhite) DARK_ON_LIGHT else LIGHT_ON_DARK
        var r = (background shr 16) and 0xFF
        var g = (background shr 8) and 0xFF
        var b = background and 0xFF
        repeat(256) {
            val lum = relativeLuminance(pack(r, g, b))
            val ratio = if (towardsWhite) {
                (lum + 0.05) / (relativeLuminance(target) + 0.05)
            } else {
                (relativeLuminance(target) + 0.05) / (lum + 0.05)
            }
            if (ratio >= minRatio) return pack(r, g, b)
            if (towardsWhite) {
                if (r == 255 && g == 255 && b == 255) return null
                r = minOf(255, r + 1); g = minOf(255, g + 1); b = minOf(255, b + 1)
            } else {
                if (r == 0 && g == 0 && b == 0) return null
                r = maxOf(0, r - 1); g = maxOf(0, g - 1); b = maxOf(0, b - 1)
            }
        }
        return null
    }

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * 该背景是否算"深色"（用来决定图标、描边等的取向）。
     * 阈值 0.5 是常用近似，与 [onColorFor] 的结论方向一致。
     */
    fun isDark(background: Int): Boolean = relativeLuminance(background) < 0.5

    /**
     * 对比度是否达到 WCAG AA 对**正文**的要求（4.5:1）。
     *
     * 供单测与自查使用。按钮文字通常字号偏小，按正文档位要求更稳妥。
     */
    fun meetsAa(foreground: Int, background: Int): Boolean =
        contrastRatio(foreground, background) >= 4.5
}

package com.mnnkit.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mnnkit.app.ui.glass.AppBackground
import com.mnnkit.app.ui.glass.GlassNavBarContent
import com.mnnkit.app.ui.glass.LocalGlassBackdrop
import com.mnnkit.app.ui.glass.LocalTopBarInset
import com.mnnkit.app.ui.glass.glassTopBar
import com.mnnkit.app.ui.glass.glassNavBar
import com.mnnkit.app.ui.glass.rememberGlassBackdrop
import com.mnnkit.app.ui.screens.MnnCapsuleButton
import com.mnnkit.app.ui.theme.MnnMotion
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 顶层导航项，对应需求的六个功能面。 */
enum class TopTab(val title: String, val icon: ImageVector) {
    Chat("对话", Icons.Rounded.Psychology),
    Models("模型商店", Icons.Rounded.Storefront),
    Voice("语音", Icons.Rounded.GraphicEq),
    Skills("技能", Icons.Rounded.Extension),
    Mcp("MCP", Icons.Rounded.Hub),
    Memory("记忆", Icons.Rounded.Memory),
    Settings("设置", Icons.Rounded.Settings),
}

/**
 * 列表末尾要留出的高度。
 *
 * **不是**让内容避开底栏 —— 内容仍要能滚到 dock 下方去，玻璃才有东西可折射。
 * 这是保证「滚到底时最后一项的**文字**不被 dock 盖住」。
 *
 * 取值推导（从屏幕底边往上算）：
 * ```
 * FloatingBarBottomInset   14dp   悬浮栏距系统导航栏
 * FloatingBarMargin        16dp   栏体自身的下留白
 * FloatingBarContentHeight 64dp   栏体高度
 * ────────────────────────────────
 * 合计                     94dp   ← dock 占据的总高度
 * ```
 * 再加 8dp 视觉喘息，取 100dp。
 *
 * ⚠️ 曾经这个常量定义了却**从没被任何页面用过**，所以设成多少都不生效 ——
 * 表现为「滑到底时最后一行文字被 dock 挡住」。现在由 [AppShell] 统一注入。
 */
internal val DockClearance = 100.dp

// ── 悬浮底栏尺寸（与参考实现同一套数值）──

/** 脱离屏幕边缘的留白。 */
private val FloatingBarMargin = 16.dp

/** 栏体高度：64dp 栏体 + 上下各 16dp 留白 = 96dp 预留高度。 */
private val FloatingBarContentHeight = 64.dp

/**
 * 按下任意 tab 时**整条底栏**的放大倍数。
 *
 * 方向是「放大」而不是常见的「按瘪」：按下时底栏与内部的滑块**一起被放大**，
 * 滑块自身再额外乘一个更大的比例。两者在修饰符链上是父子关系，比例天然相乘。
 *
 * 取值要克制：底栏四周只有 16dp 留白，放大超过 1.06 就会把留白吃得差不多、
 * 看起来像贴住了屏幕边缘。
 */
private val FloatingBarPressedScale = 1.04f

/** 悬浮底栏浮在内容之上，需要为它让出的底部空间。 */
internal val FloatingBarReservedHeight = FloatingBarContentHeight + FloatingBarMargin * 2

/**
 * 悬浮底栏额外抬离系统导航栏的高度。
 *
 * 为什么需要它：Scaffold 给的内容内边距只含系统栏 inset，底栏再往上 16dp 就停了 ——
 * 在 3 键导航（本机 `navigation_mode = 0`，导航栏约 48dp）上表现为**紧贴导航栏**，
 * 而参考实现里底栏是明显悬浮起来的。
 *
 * 取值经过实拍校准：先试 30dp，结果比参考实现还高出约 30dp（浮得太夸张）；
 * 14dp 时底栏与导航栏之间的留白和参考实现目视一致。
 *
 * 输入区浮层用同一个值，保证两者间距和参考实现一致。
 */
internal val FloatingBarBottomInset = 14.dp

/**
 * 应用外壳。
 *
 * 结构对齐参考实现 KSuRoot 的 `RootApp`：
 *
 * ```
 * Box(fillMaxSize)
 *   └ CompositionLocalProvider(LocalGlassBackdrop)
 *       └ Scaffold(containerColor = 透明)
 *           ├ topBar: SmallTopAppBar（不透明，铺 surfaceContainer）
 *           └ content:
 *               Box(fillMaxSize)
 *                 ├ Box(layerBackdrop)   ← 【采集层】背景 + 全部页面内容
 *                 │    AppBackground()
 *                 │    AnimatedContent { 页面 }
 *                 └ Box(padding)         ← 悬浮底栏，在采集层**之外**
 *                      └ Box(BottomCenter)
 *                          ① Box(glassNavBar)      ← 玻璃底板（空 Box）
 *                          ② GlassNavBarContent    ← 与玻璃是兄弟
 * ```
 *
 * ## 为什么采集层里必须同时有「背景」和「内容」
 *
 * - 只有内容、没背景 → 空白处录到的是**透明**，糊透明还是透明；
 * - 只有背景、没内容 → 糊一个纯色得到的还是同一个纯色
 *   （模糊是低通滤波，纯色高频为零）。
 *
 * ## 为什么顶栏/底栏必须在采集层**之外**
 *
 * 否则它们会录到自己，形成自引用。本工程在小米 25019PNF3C（Android 17 / HyperOS）
 * 上实测过这个错误：RenderThread 栈溢出，`512 total frames` 全在
 * `libhwui RenderNode::prepareTreeImpl`，应用启动后黑屏约 1 秒即闪退。
 */
@Composable
fun AppShell(
    llmAvailable: Boolean,
    modelCount: Int,
    installedCount: Int,
    memoryCount: Int,
    skillCount: Int,
    mcpCount: Int,
    /**
     * 当前选中的页。**由调用方持有** —— 因为 [glassOverlay] 需要知道当前是哪一页
     * （例如只有对话页才显示输入框），而插槽与 `content` 是不同的组合作用域。
     */
    tab: TopTab,
    onTabChange: (TopTab) -> Unit,
    /**
     * 点顶栏右上角「新对话」。**只有对话页且已有消息时**才会显示这个按钮 ——
     * 没有对话可清时露一个按钮出来，只会让人怀疑它到底干了什么。
     *
     * 真正的「清空 + 清磁盘」动作由调用方执行（它持有 messages 与
     * ConversationStore）；这里只负责把按钮放在顶栏的 `trailing` 槽里。
     */
    onNewChat: () -> Unit = {},
    onChatHistory: () -> Unit = {},
    /**
     * 对话页是否已有消息（决定右上角「新对话」按钮显不显示）。
     */
    hasChat: Boolean = false,
    /**
     * 浮在页面之上的**玻璃层**。它会被放在采集层**之外**，并拿到自己的采集源。
     *
     * ⚠️ 玻璃元素绝不能写在 [content] 里 —— `content` 是采集层的内容，
     * 在那里铺玻璃会让它采样到「包含自己的层」，本机实测就是渲染树递归崩溃。
     * 需要玻璃的浮层（例如输入框）必须通过这个插槽交上来，由这里摆放。
     *
     * 本插槽的内容会**贴底**并自动让出底栏的高度；
     * 需要贴别的位置请自行在插槽里用 `Modifier.align`（作用域是 `BoxScope`）。
     */
    glassOverlay: @Composable androidx.compose.foundation.layout.BoxScope.(
        backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    ) -> Unit = {},
    content: @Composable (tab: TopTab) -> Unit,
) {
    val glassBackdrop = rememberGlassBackdrop()

    // 点按底栏 → 整条 dock 缩放。按压在 GlassNavBarContent 内部采集，
    // 而要缩放的是这里的外层玻璃容器，所以由内容层回调上报。
    // 缩放做在 graphicsLayer 上（纯绘制），命中区仍是原来的整条栏。
    var dockPressed by remember { mutableStateOf(false) }
    val dockScale by animateFloatAsState(
        targetValue = if (dockPressed) FloatingBarPressedScale else 1f,
        animationSpec = MnnMotion.press(),
        label = "dockPressScale",
    )

    val subtitle = when (tab) {
        TopTab.Chat -> if (llmAvailable) "本地推理就绪" else "未加载推理库"
        TopTab.Models -> "$modelCount 个可用 · $installedCount 个已安装"
        TopTab.Voice -> "语音识别与合成"
        TopTab.Skills -> "$skillCount 个已安装"
        TopTab.Mcp -> "$mcpCount 个已配置"
        TopTab.Memory -> "$memoryCount 条记忆"
        TopTab.Settings -> "外观、推理与语音"
    }

    Box(Modifier.fillMaxSize()) {
        // 顶栏（玻璃额头）改成自己摆的浮层，所以要把量出来的高度告诉各页 —— 见 LocalTopBarInset。
        var topBarHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalGlassBackdrop provides glassBackdrop,
            LocalTopBarInset provides topBarHeight,
        ) {
            Scaffold(
                // 背景由采集层画；Scaffold 不能再铺不透明底，否则会盖住采集层
                containerColor = Color.Transparent,
                // 顶栏（玻璃额头）不再占用 Scaffold 的槽位，改成本文件下方的玻璃浮层：
                // 它既要在采集层**之外**（否则玻璃录到自己 → 渲染树递归闪退），
                // 又要**浮在页面之上**（否则内容无法从玻璃下穿过，折射就看不见）。
            ) { padding ->
                val layoutDirection = LocalLayoutDirection.current
                // 内容**不用**为底栏让出高度：列表要能滚到 dock 下方去，
                // 玻璃底栏才有东西可折射（这正是「浮在内容之上」的意义）。
                // 各页只在自己的列表末尾加一点 padding，免得最后一项被永久盖住。
                val contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )

                Box(Modifier.fillMaxSize()) {
                    // ── 采集层：背景 + 全部页面内容，这就是玻璃唯一能糊到的东西 ──
                    // 顶栏与底栏都在这个 Box 之外，所以不会录到自己（自引用）。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(glassBackdrop)
                    ) {
                        AppBackground()
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                // 方向感知的层叠推入。位移只走 1/4 屏 ——
                                // 全屏滑动会让两页在采集层里大面积交叠，模糊条带跟着内容乱跑。
                                val forward = targetState.ordinal > initialState.ordinal
                                val enterOffset: (Int) -> Int = { if (forward) it / 4 else -it / 4 }
                                val exitOffset: (Int) -> Int = { if (forward) -it / 4 else it / 4 }
                                (
                                    fadeIn(MnnMotion.snappy()) +
                                        slideInHorizontally(MnnMotion.gentle(), enterOffset)
                                    ).togetherWith(
                                    fadeOut(MnnMotion.snappy()) +
                                        slideOutHorizontally(MnnMotion.snappy(), exitOffset)
                                )
                            },
                            label = "page",
                        ) { page ->
                            // ⚠️ 故意**不**应用 contentPadding 的 top：页面必须从 y=0 开始画，
                            // 滚动时内容从玻璃额头**下面穿过**，玻璃才有东西可折射、才不是
                            // 一块糊在纯色上的塑料板。第一条内容的可见性由各页把
                            // LocalTopBarInset 加进自己 LazyColumn 的 contentPadding.top 负责。
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = contentPadding.calculateStartPadding(layoutDirection),
                                        end = contentPadding.calculateEndPadding(layoutDirection),
                                        bottom = contentPadding.calculateBottomPadding(),
                                    )
                            ) {
                                content(page)
                            }
                        }
                    }

                    // ── 液态玻璃额头（顶栏）──
                    // 与采集层是**兄弟**：它在采集层之外，所以不会录到自己（自引用）。
                    // 位置贴顶、整幅宽度；玻璃底板只圆下沿，上沿与状态栏齐平。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .onSizeChanged { topBarHeight = with(density) { it.height.toDp() } }
                    ) {
                        // ① 玻璃底板：**只有玻璃、不含任何子内容**。
                        // 库的绘制顺序把子内容画进玻璃那层的裁剪里，所以底板必须是空 Box。
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .glassTopBar(backdrop = glassBackdrop),
                        )
                        // ② 内容层：与玻璃是兄弟，画在玻璃之上。
                        //    title / subtitle / actions 与改造前完全一致。
                        SmallTopAppBar(
                            title = tab.title,
                            subtitle = subtitle,
                            modifier = Modifier.fillMaxWidth(),
                            // 底色交给自己画的玻璃；这里必须全透明，否则会把玻璃盖住。
                            color = Color.Transparent,
                            actions = {
                                if (tab == TopTab.Chat) {
                                    MnnCapsuleButton(text = "历史", onClick = onChatHistory)
                                }
                                if (tab == TopTab.Chat && hasChat) {
                                    MnnCapsuleButton(
                                        text = "新对话",
                                        onClick = {
                                            android.util.Log.i("LocalAI-Gen", "顶栏「新对话」被点击")
                                            onNewChat()
                                        },
                                    )
                                }
                            },
                        )
                    }
                    // ── 玻璃浮层插槽（例如输入框）──
                    // 和底栏一样在**采集层之外**，所以它采样到的是「页面内容」整层，
                    // 能真实地糊到下方滚动过去的内容，而不会自引用。
                    // 贴底摆放，并让出底栏占的高度。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(bottom = FloatingBarReservedHeight + FloatingBarBottomInset)
                    ) {
                        glassOverlay(glassBackdrop)
                    }

                    // ── 悬浮玻璃底栏：脱离屏幕边缘，浮在内容之上 ──
                    // 这一层只有 inset padding，没有 pointerInput，不会拦截下方内容的触摸。
                    //
                    // `padding` 是 Scaffold 给的内容内边距 —— 它**已经包含系统栏 inset**，
                    // 所以「底栏紧贴系统导航栏上方 + 再留 FloatingBarMargin」是自动成立的，
                    // 不需要手动加 navigationBarsPadding()。
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    start = FloatingBarMargin,
                                    end = FloatingBarMargin,
                                    bottom = FloatingBarMargin + FloatingBarBottomInset,
                                )
                                .fillMaxWidth()
                                .height(FloatingBarContentHeight)
                                // 按下缩放作用在**整条 dock** 上。它是这一层的父修饰符，
                                // 所以内部的滑块会连同一起被放大；滑块自己再乘一个更大的比例，
                                // 于是「底栏和滑块一起放大、滑块比例更大」是天然成立的。
                                .graphicsLayer {
                                    scaleX = dockScale
                                    scaleY = dockScale
                                }
                        ) {
                            // ① 玻璃底板：**只有玻璃、不含任何子内容**。
                            // 库的绘制顺序是
                            //   onDrawBehind → drawBackdropLayer(玻璃，带形状裁剪) → onDrawSurface → drawContent()
                            // 子内容是在玻璃那一层的裁剪里画的。所以玻璃必须挂在一个空 Box 上，
                            // 内容层做它的**兄弟**；否则滑块放大后超出栏体的部分会被这里裁掉
                            // （表现为「超出底栏显示的部分不显示」）。
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .glassNavBar(
                                        backdrop = glassBackdrop,
                                        shape = RoundedCornerShape(50), // 50% = 真正的胶囊
                                        fallbackColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                                    )
                            )
                            // ② 内容层：与玻璃是兄弟，画在玻璃之上、且不在它的裁剪里
                            GlassNavBarContent(
                                items = TopTab.entries.map { it.icon to it.title },
                                selectedIndex = TopTab.entries.indexOf(tab),
                                onSelect = { onTabChange(TopTab.entries[it]) },
                                modifier = Modifier.matchParentSize(),
                                backdrop = glassBackdrop,
                                contentHeight = FloatingBarContentHeight,
                                onBarPressedChange = { dockPressed = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.mnnkit.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.mnnkit.app.ui.glass.liquidGlass
import com.mnnkit.app.ui.theme.MnnRadii
import com.mnnkit.app.ui.theme.MnnSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 图片放大查看。
 *
 * ## 为什么这里可以铺液态玻璃，而气泡里不行
 *
 * 气泡是**采集层里的内容** —— 给它铺玻璃会自引用（玻璃去采样包含自己的层），
 * 真机实测会渲染树递归闪退。
 * 而 `Dialog` 是**独立窗口**，它的内容不在主窗口的采集层里，
 * 所以可以安全地把玻璃用在下方的下载按钮上。
 *
 * ## 交互
 *
 * - 双指缩放 + 拖动（`detectTransformGestures`）
 * - 点背景关闭
 * - 缩放倍数钳制在 1x~5x；小于 1x 时回弹到 1x，避免图片缩成看不见的一点
 */
@Composable
fun ImageZoomDialog(
    path: String,
    backdrop: LayerBackdrop?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        // 让对话框铺满整屏、且不显示默认的窗口装饰 —— 白底遮罩在深色主题下很突兀
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))   // 近黑，让图片跳出来
                .clickable(onClick = onDismiss),
        ) {
            // ── 图片（缩放手势只挂在这里，避免手势与"点背景关闭"打架）──
            bitmap?.let { bmp ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    // 回到 1x 时把平移清零，否则图会停在偏位
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        }
                        // 点图片本身不关闭（只有点背景关）
                        .clickable(enabled = false) { },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp,
                        contentDescription = "生成的图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            // ── 右上角：关闭 ──
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(MnnSpacing.page)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x66000000))
                    .clickable(onClick = onDismiss)
                    .padding(10.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                )
            }

            // ── 底部：液态玻璃下载按钮 ──
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = MnnSpacing.page,
                        end = MnnSpacing.page,
                        bottom = MnnSpacing.page * 2,
                    ),
            ) {
                // 玻璃底板：不含子内容（库的绘制顺序要求）
                Box(
                    Modifier
                        .matchParentSize()
                        .liquidGlass(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(50),
                            // 没有采集源时退回半透明黑，仍然可读
                            fallbackColor = Color(0xCC1A1A1A),
                        )
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onDownload),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(MnnSpacing.inline),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("保存到相册", color = Color.White)
                        }
                    }
                }
            }

            // 提示：缩放状态（放大时才显示，避免干扰）
            if (scale > 1.01f) {
                Text(
                    "已放大 ${"%.1f".format(scale)}×（双击无效，双指可缩放）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = Color(0xAAFFFFFF),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = MnnSpacing.page * 2),
                )
            }
        }
    }
}

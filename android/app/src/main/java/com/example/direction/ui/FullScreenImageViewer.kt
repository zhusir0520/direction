package com.example.direction.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏图片查看器
 *
 * 支持手势缩放、平移和双击恢复。
 * 使用Dialog实现全屏覆盖。
 *
 * @param bitmap 要显示的Bitmap（可为null）
 * @param onDismiss 关闭查看器的回调
 * @param imageDescription 图片描述（用于无障碍）
 */
@Composable
fun FullScreenImageViewer(
    bitmap: Bitmap?,
    onDismiss: () -> Unit,
    imageDescription: String = "全屏图片"
) {
    if (bitmap == null) {
        // 如果图片为空，显示空状态并自动关闭
        LaunchedEffect(Unit) {
            onDismiss()
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        FullScreenImageContent(
            bitmap = bitmap,
            onDismiss = onDismiss,
            imageDescription = imageDescription
        )
    }
}

/**
 * 全屏图片内容（内部实现）
 */
@Composable
private fun FullScreenImageContent(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    imageDescription: String
) {
    // 手势状态
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, rotationChange ->
        // 更新缩放（限制在0.5x到10x之间）
        val newScale = scale * zoomChange
        scale = newScale.coerceIn(0.5f, 10f)

        // 更新偏移
        offset += panChange * scale
    }

    // 双击状态
    var isDoubleTapEnabled by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (isDoubleTapEnabled) {
                            // 双击：如果缩放大于1.5，则重置；否则放大到3倍
                            if (scale > 1.5f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 3f
                                // 将双击点调整到视图中心
                                val centerOffset = Offset(
                                    -tapOffset.x * (scale - 1),
                                    -tapOffset.y * (scale - 1)
                                )
                                offset = centerOffset
                            }
                        }
                    },
                    onTap = {
                        // 单机关闭查看器
                        onDismiss()
                    }
                )
            }
    ) {
        // 图片显示区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformableState)
                .clipToBounds()
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = imageDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }

        // 顶部信息栏（显示分辨率和关闭按钮）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            color = Color.Black.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 分辨率信息
                Text(
                    text = "${bitmap.width} × ${bitmap.height}",
                    color = Color.White,
                    fontSize = 14.sp
                )

                // 缩放比例信息
                Text(
                    text = "${"%.1f".format(scale)}x",
                    color = Color.White,
                    fontSize = 14.sp
                )

                // 关闭按钮
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White
                    )
                }
            }
        }

    }
}

/**
 * 简单的全屏图片查看器（简化版，无手势）
 * 用于快速预览
 */
@Composable
fun SimpleFullScreenImageViewer(
    bitmap: Bitmap?,
    onDismiss: () -> Unit,
    imageDescription: String = "全屏图片"
) {
    if (bitmap == null) {
        LaunchedEffect(Unit) {
            onDismiss()
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = imageDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // 顶部关闭按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
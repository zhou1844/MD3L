package launcher.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import launcher.core.DownloadHub

/**
 * 全局下载悬浮球 — 在有任务时显示于右下角。
 *
 * - 圆形按钮，用描边弧度显示总体进度
 * - 点击后导航到下载管理页面
 */
@Composable
fun DownloadFab(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tasks by DownloadHub.tasks.collectAsState()
    val hasRunning = tasks.any { it.status == DownloadHub.TaskStatus.Running }
    val hasAny = tasks.isNotEmpty()

    // 动画：弹入/弹出
    AnimatedVisibility(
        visible = hasAny,
        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        val running = tasks.filter { it.status == DownloadHub.TaskStatus.Running }
        val fraction = if (running.isEmpty()) 1f
        else running.map { it.fraction }.average().toFloat()

        // 动画化进度值
        val animFraction by animateFloatAsState(
            targetValue = fraction,
            animationSpec = tween(300),
        )

        // 活跃任务数 badge
        val activeCount = running.size

        val primaryColor = MaterialTheme.colorScheme.primary
        val trackColor = MaterialTheme.colorScheme.surfaceVariant

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .drawBehind {
                    // 进度环（背景轨道）
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(4f, 4f),
                        size = Size(size.width - 8f, size.height - 8f),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                    )
                    // 进度环（前景）
                    if (hasRunning) {
                        drawArc(
                            color = primaryColor,
                            startAngle = -90f,
                            sweepAngle = 360f * animFraction,
                            useCenter = false,
                            topLeft = Offset(4f, 4f),
                            size = Size(size.width - 8f, size.height - 8f),
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                },
        ) {
            if (hasRunning) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载中",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "完成",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // badge
        if (activeCount > 0) {
            Box(modifier = Modifier.size(56.dp)) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp),
                ) {
                    Text("$activeCount")
                }
            }
        }
    }
}

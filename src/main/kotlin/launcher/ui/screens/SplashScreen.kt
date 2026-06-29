package launcher.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import launcher.ui.theme.ThemeState

/**
 * Material Design 3 风格启动动画。
 *
 * 动画序列（总时长 ≈ 2200ms）：
 * 1. Logo 图标淡入 + 向上位移（0–500ms）— MD3 Emphasized Decelerate
 * 2. 圆形进度环旋转 + 描边动画（250–1600ms）
 * 3. 底部文字淡入（700–1200ms）
 * 4. 整体保持显示直到动画结束
 *
 * 动画结束后通过 [onAnimationEnd] 回调通知调用方。
 *
 * 使用 exe 同款图标（app_icon.png），背景圆角裁剪。
 */
@Composable
fun SplashScreen(
    onAnimationEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEn = ThemeState.language == "en"

    // ── MD3 标准缓动曲线 ──────────────────────────────────────────────────────
    // Emphasized Decelerate: 进场——从快到慢，感觉内容「落定」
    val md3EmphasizedDecelerate = remember { CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f) }
    // Emphasized Accelerate: 退场——从慢到快，感觉内容「飞走」
    val md3EmphasizedAccelerate = remember { CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f) }
    // Standard Decelerate: 用于淡入淡出，更柔和
    val md3StandardDecelerate = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) }
    // Standard Accelerate
    val md3StandardAccelerate = remember { CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f) }

    // ── 动画状态 ──────────────────────────────────────────────────────────────
    val logoAlpha = remember { Animatable(0f) }
    val logoOffsetY = remember { Animatable(30f) }
    val rotation = remember { Animatable(0f) }
    val sweepAngle = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    // 启动动画序列
    LaunchedEffect(Unit) {
        // 阶段1: Logo 淡入 + 上移（0–500ms）— MD3 Emphasized Decelerate
        launch {
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(500, easing = md3EmphasizedDecelerate)
            )
        }
        launch {
            logoOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(500, easing = md3EmphasizedDecelerate)
            )
        }

        // 阶段2: 进度环动画（250ms 后开始）
        delay(250)
        launch {
            // 旋转持续旋转 — 使用更平滑的 1000ms 周期
            while (true) {
                rotation.animateTo(
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    )
                )
            }
        }
        launch {
            // 描边先伸后缩 — MD3 Standard Decelerate/Accelerate
            sweepAngle.animateTo(
                targetValue = 270f,
                animationSpec = tween(650, easing = md3StandardDecelerate)
            )
            sweepAngle.animateTo(
                targetValue = 0f,
                animationSpec = tween(450, easing = md3StandardAccelerate)
            )
        }

        // 阶段3: 文字淡入（700ms 后开始）
        delay(700)
        textAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(500, easing = md3EmphasizedDecelerate)
        )

        // 等待动画全部完成
        delay(700)
        // 保持一小段时间让用户看到完整画面
        delay(300)

        // 通知动画结束
        onAnimationEnd()
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 加载 exe 同款图标
    val appIconPainter = painterResource("app_icon.png")

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .offset(y = logoOffsetY.value.dp)
                .alpha(logoAlpha.value),
        ) {
            // ── 使用 exe 同款图标 ──────────────────────────────────────────
            Image(
                painter = appIconPainter,
                contentDescription = "MD3L Logo",
                modifier = Modifier.size(80.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── 名称 ───────────────────────────────────────────────────────
            Text(
                text = "MD3L",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                ),
                color = primaryColor,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── 副标题 ─────────────────────────────────────────────────────
            Text(
                text = if (isEn) "Material Design 3 Launcher" else "Material Design 3 启动器",
                style = MaterialTheme.typography.bodyMedium.copy(
                    letterSpacing = 1.sp,
                ),
                color = onSurfaceVariantColor.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(48.dp))

            // ── 进度环 ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(32.dp)) {
                    val strokeWidth = 2.5f
                    val sweep = sweepAngle.value.coerceAtLeast(5f)

                    // 背景环
                    drawArc(
                        color = primaryColor.copy(alpha = 0.12f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )

                    // 前景环
                    drawArc(
                        color = primaryColor,
                        startAngle = rotation.value - 90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 加载提示文字 ───────────────────────────────────────────────
            Text(
                text = if (isEn) "Loading…" else "正在加载…",
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 0.5.sp,
                ),
                color = onSurfaceVariantColor.copy(alpha = 0.4f),
                modifier = Modifier.alpha(textAlpha.value),
            )
        }
    }
}

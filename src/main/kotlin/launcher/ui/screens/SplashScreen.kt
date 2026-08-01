package launcher.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

@Composable
fun SplashScreen(
    onAnimationEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEn = ThemeState.language == "en"

    val md3EmphasizedDecelerate = remember { CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f) }
    val md3EmphasizedAccelerate = remember { CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f) }
    val md3StandardDecelerate = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f) }
    val md3StandardAccelerate = remember { CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f) }

    val logoAlpha = remember { Animatable(0f) }
    val logoOffsetY = remember { Animatable(30f) }
    val rotation = remember { Animatable(0f) }
    val sweepAngle = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
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

        delay(250)
        launch {
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
            sweepAngle.animateTo(
                targetValue = 270f,
                animationSpec = tween(650, easing = md3StandardDecelerate)
            )
            sweepAngle.animateTo(
                targetValue = 0f,
                animationSpec = tween(450, easing = md3StandardAccelerate)
            )
        }

        delay(700)
        textAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(500, easing = md3EmphasizedDecelerate)
        )

        delay(700)
        delay(300)

        onAnimationEnd()
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val appIconPainter = painterResource("app_icon.png")

    Box(
        modifier = modifier
            .fillMaxSize()
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
            Image(
                painter = appIconPainter,
                contentDescription = "MD3L Logo",
                modifier = Modifier.size(80.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "MD3L",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                ),
                color = primaryColor,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isEn) "Material Design 3 Launcher" else "Material Design 3 启动器",
                style = MaterialTheme.typography.bodyMedium.copy(
                    letterSpacing = 1.sp,
                ),
                color = onSurfaceVariantColor.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(48.dp))

            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(32.dp)) {
                    val strokeWidth = 2.5f
                    val sweep = sweepAngle.value.coerceAtLeast(5f)

                    drawArc(
                        color = primaryColor.copy(alpha = 0.12f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )

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

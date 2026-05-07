package launcher.ui.layout

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import launcher.core.GameProcessManager
import launcher.core.LaunchState
import launcher.ui.components.DownloadFab
import launcher.ui.nav.Route
import launcher.ui.nav.Screen
import launcher.ui.nav.primaryTab
import launcher.ui.screens.*

object Navigator {
    var current by mutableStateOf<Route>(Route.Launch)
        private set

    private val backStack = mutableListOf<Route>()

    fun navigate(route: Route) {
        backStack.add(current)
        current = route
    }

    fun back() {
        if (backStack.isNotEmpty()) {
            current = backStack.removeLast()
        }
    }

    fun navigatePrimary(screen: Screen) {
        backStack.clear()
        current = when (screen) {
            Screen.Launch -> Route.Launch
            Screen.Versions -> Route.Versions
            Screen.Download -> Route.Download
            Screen.Mods -> Route.Mods
            Screen.Settings -> Route.Settings
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLayout(modifier: Modifier = Modifier) {
    val currentRoute = Navigator.current
    val activeTab = currentRoute.primaryTab()
    val isLaunching by LaunchState.isLaunching.collectAsState()
    val activeProcess by GameProcessManager.activeProcess.collectAsState()
    val uiLocked = isLaunching || activeProcess != null

    Row(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ── Left: NavigationRail ──────────────────────────────────────────────
        NavigationRail(
            modifier = Modifier
                .fillMaxHeight()
                .width(80.dp)
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .alpha(if (uiLocked) 0.45f else 1f),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            header = {
                Text(
                    text = "MD3L",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        fontSize = 14.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
                )
            },
        ) {
            Spacer(Modifier.height(2.dp))
            Screen.entries.forEach { screen ->
                val selected = activeTab == screen
                NavigationRailItem(
                    selected = selected,
                    onClick = { if (!uiLocked) Navigator.navigatePrimary(screen) },
                    enabled = !uiLocked,
                    icon = {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.label,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    label = {
                        Text(
                            text = screen.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Spacer(Modifier.height(2.dp))
            }

            Spacer(Modifier.weight(1f))
        }

        // ── Right: Main content with Crossfade + 悬浮球 ──────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            AnimatedContent(
                targetState = currentRoute,
                transitionSpec = {
                    val enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                            slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 20 }
                    val exit = fadeOut(tween(150))
                    enter togetherWith exit
                },
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) { route ->
                when (route) {
                    is Route.Launch -> LaunchScreen()
                    is Route.Versions -> VersionScreen()
                    is Route.Download -> DownloadScreen()
                    is Route.Mods -> ModScreen()
                    is Route.Settings -> SettingsScreen()
                    is Route.VersionDetail -> VersionDetailScreen(route.version)
                    is Route.ModDetail -> ModDetailScreen(route.project, route.edition, route.contentType)
                    is Route.DownloadManager -> DownloadManagerScreen()
                }
            }

            // ── 下载悬浮球（始终悬浮在右下角，不随页面切换消失）──
            DownloadFab(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                onClick = { Navigator.navigate(Route.DownloadManager) },
            )
        }
    }
}

package launcher.ui.screens

import launcher.ui.layout.NavBarScrollState

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import launcher.core.MultiplayerManager
import launcher.ui.theme.ThemeState

// ═════════════════════════════════════════════════════════════════════════════
//  Terracotta 联机主界面
//  对应 HMCL TerracottaControllerPage 的设计
// ═════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerScreen() {
    val isEn = ThemeState.language == "en"
    val scope = rememberCoroutineScope()

    // 订阅 MultiplayerManager 状态
    val connectionState by MultiplayerManager.state.collectAsState()
    val statusMessage by MultiplayerManager.statusMessage.collectAsState()
    val errorMessage by MultiplayerManager.errorMessage.collectAsState()
    val downloadProgress by MultiplayerManager.downloadProgress.collectAsState()
    val roomInfo by MultiplayerManager.roomInfo.collectAsState()
    val serverAddress by MultiplayerManager.serverAddress.collectAsState()

    var roomCodeInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // 初始化 Terracotta
    LaunchedEffect(Unit) {
        MultiplayerManager.initialize()
    }

    // 监听滚动位置
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val canScrollForward = scrollState.canScrollForward
            val value = scrollState.value.toFloat()
            val maxValue = scrollState.maxValue.toFloat()
            if (maxValue <= 0f) 0f
            else if (!canScrollForward) 1f
            else (value / maxValue).coerceIn(0f, 0.99f)
        }.collect { fraction ->
            NavBarScrollState.scrollFraction.value = fraction
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose { /* 保持 Terracotta 运行 */ }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ── 顶部标题 ──────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(
                Icons.Filled.Groups,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (isEn) "Multiplayer" else "联机",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            // Terracotta 版本标签
            AssistChip(
                onClick = {},
                label = { Text("Terracotta 0.4.2", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(14.dp)) },
            )
        }

        // ── 根据状态渲染不同内容 ─────────────────────────────────
        when (connectionState) {
            MultiplayerManager.State.Uninitialized -> {
                UninitializedContent(isEn = isEn)
            }
            MultiplayerManager.State.Downloading -> {
                DownloadingContent(isEn = isEn, progress = downloadProgress, message = statusMessage)
            }
            MultiplayerManager.State.Installing -> {
                InstallingContent(isEn = isEn, message = statusMessage)
            }
            MultiplayerManager.State.Launching -> {
                LaunchingContent(isEn = isEn, message = statusMessage)
            }
            MultiplayerManager.State.Unknown, MultiplayerManager.State.Idle -> {
                IdleContent(
                    isEn = isEn,
                    roomCodeInput = roomCodeInput,
                    onRoomCodeChange = { roomCodeInput = it.uppercase().take(32) },
                    onCreateRoom = { MultiplayerManager.createRoom() },
                    onJoinRoom = { MultiplayerManager.joinRoom(roomCodeInput) },
                )
            }
            MultiplayerManager.State.HostScanning -> {
                ScanningContent(isEn = isEn, message = statusMessage)
            }
            MultiplayerManager.State.HostOK -> {
                HostOkContent(
                    isEn = isEn,
                    roomInfo = roomInfo,
                    serverAddress = serverAddress,
                    onReset = { MultiplayerManager.cancelToIdle() },
                )
            }
            MultiplayerManager.State.GuestConnecting -> {
                ConnectingContent(isEn = isEn, message = statusMessage)
            }
            MultiplayerManager.State.GuestOK -> {
                GuestOkContent(
                    isEn = isEn,
                    serverAddress = serverAddress,
                    onReset = { MultiplayerManager.cancelToIdle() },
                )
            }
            MultiplayerManager.State.Exception -> {
                ExceptionContent(
                    isEn = isEn,
                    error = errorMessage,
                    onRecover = { MultiplayerManager.recover() },
                )
            }
            MultiplayerManager.State.Fatal -> {
                FatalContent(
                    isEn = isEn,
                    error = errorMessage,
                    onRetry = { MultiplayerManager.recover() },
                )
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Uninitialized — 需要下载
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun UninitializedContent(isEn: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        Icon(
            Icons.Filled.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isEn) "Terracotta Not Installed" else "Terracotta 尚未安装",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isEn)
                "Terracotta is a network tool that enables multiplayer through relay servers. Click below to download and set it up."
            else
                "Terracotta 是通过中继服务器实现远程联机的网络工具。点击下方下载并配置。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { MultiplayerManager.startDownload() },
            modifier = Modifier.height(48.dp),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isEn) "Download Terracotta" else "下载 Terracotta")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isEn)
                "This will download ~5MB from GitHub/Gitee. SHA-512 verified."
            else
                "将从 GitHub/Gitee 下载约 5MB，SHA-512 校验。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Downloading — 下载中
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun DownloadingContent(isEn: Boolean, progress: Float, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        Text(
            text = message.ifBlank { if (isEn) "Downloading..." else "正在下载..." },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(0.7f).height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Installing — 解压安装中
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun InstallingContent(isEn: Boolean, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message.ifBlank { if (isEn) "Installing..." else "正在安装..." },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Launching — 启动 Terracotta 进程
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun LaunchingContent(isEn: Boolean, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message.ifBlank { if (isEn) "Starting Terracotta..." else "正在启动 Terracotta..." },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isEn) "This may take a few seconds" else "可能需要几秒钟",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Idle — 就绪，选择操作
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun IdleContent(
    isEn: Boolean,
    roomCodeInput: String,
    onRoomCodeChange: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
) {
    // ── 创建房间 ────────────────────────────────────────────────
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isEn) "Create a Room" else "创建房间",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isEn)
                    "Start hosting and get a room code. Share it with friends so they can join your game."
                else
                    "创建主机并获取房间码，分享给好友即可加入你的游戏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCreateRoom,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isEn) "Create Room" else "创建房间")
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // ── 加入房间 ────────────────────────────────────────────────
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Login, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isEn) "Join a Room" else "加入房间",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isEn)
                    "Enter the room code shared by the host to join their game."
                else
                    "输入主机分享的房间码来加入游戏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = roomCodeInput,
                    onValueChange = onRoomCodeChange,
                    placeholder = { Text(if (isEn) "Room Code" else "房间码") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.sp),
                )
                Button(
                    onClick = onJoinRoom,
                    enabled = roomCodeInput.length >= 4,
                ) {
                    Text(if (isEn) "Join" else "加入")
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Scanning — 扫描中继节点
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun ScanningContent(isEn: Boolean, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message.ifBlank { if (isEn) "Scanning relay nodes..." else "正在扫描中继节点..." },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isEn) "Contacting relay servers to set up your room" else "正在联系中继服务器设置房间",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { MultiplayerManager.cancelToIdle() }) {
            Text(if (isEn) "Cancel" else "取消")
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  HostOK — 房间已创建
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun HostOkContent(
    isEn: Boolean,
    roomInfo: MultiplayerManager.RoomInfo?,
    serverAddress: String,
    onReset: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // 解析自己的玩家名（当前房主）
    val myName = remember {
        launcher.core.AccountRepository.activeAccount.value?.username ?: "MD3L_Player"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        // 状态指示灯
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isEn) "Room Created!" else "房间已创建！",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isEn) "Share this code with friends:" else "将此房间码分享给好友：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // 房间码显示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = roomInfo?.code ?: "------",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 6.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        roomInfo?.code?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isEn) "Copy Code" else "复制房间码")
                }
            }
        }

        // 玩家列表（带踢人按钮）
        if (roomInfo != null && roomInfo.profiles.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isEn) "Players in Room" else "房间内玩家",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (isEn) "(${roomInfo.profiles.size} players)" else "(${roomInfo.profiles.size} 位玩家)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            roomInfo.profiles.forEach { profile ->
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(
                            if (profile.type == "host") Icons.Filled.Star else Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (profile.type == "host") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(profile.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (profile.type == "host") (if (isEn) "Host" else "房主")
                                    else (if (isEn) "Guest" else "客机"),
                                    fontSize = 11.sp,
                                )
                            },
                        )
                        // 房主可以踢客机（不能踢自己）
                        if (profile.type != "host" && profile.name != myName) {
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { MultiplayerManager.kickPlayer(profile.name) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.PersonRemove,
                                    contentDescription = if (isEn) "Kick ${profile.name}" else "踢出 ${profile.name}",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isEn) "Keep this window open while playing. Start your Minecraft game and join automatically." else "玩游戏时请保持此窗口打开。启动 Minecraft 后会自动加入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onReset) {
            Text(if (isEn) "Close Room & Disconnect" else "关闭房间并断开")
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Connecting — 连接中
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun ConnectingContent(isEn: Boolean, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message.ifBlank { if (isEn) "Connecting to room..." else "正在连接房间..." },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isEn) "Contacting relay servers and establishing connection" else "正在联系中继服务器建立连接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { MultiplayerManager.cancelToIdle() }) {
            Text(if (isEn) "Cancel" else "取消")
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  GuestOK — 已连接
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun GuestOkContent(isEn: Boolean, serverAddress: String, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isEn) "Connected!" else "已连接！",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isEn) "You are now connected to the room. Start Minecraft to join the game." else "已连接到房间。启动 Minecraft 即可加入游戏。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (serverAddress.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(serverAddress, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onReset) {
            Text(if (isEn) "Disconnect" else "断开连接")
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Exception — 异常状态
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun ExceptionContent(
    isEn: Boolean,
    error: String?,
    onRecover: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isEn) "Connection Error" else "连接错误",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { MultiplayerManager.cancelToIdle() }) {
                Text(if (isEn) "Back" else "返回")
            }
            Button(onClick = onRecover) {
                Text(if (isEn) "Retry" else "重试")
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Fatal — 致命错误
// ═════════════════════════════════════════════════════════════════════════════
@Composable
private fun FatalContent(isEn: Boolean, error: String?, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isEn) "Fatal Error" else "严重错误",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isEn) "This may be caused by network issues or a corrupted download. Please try again." else "可能由网络问题或下载损坏导致，请重试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isEn) "Retry" else "重试")
        }
    }
}

package com.hqsrawmelon.webdavserver

import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    networkDiagnostics: NetworkDiagnostics,
    isServerRunning: Boolean,
    onRequestLocationPermission: () -> Unit = {},
) {
    // Navigation state
    var currentScreen by remember { mutableStateOf<String?>(null) }
    
    // 添加返回键处理，当在详情界面时返回主设置界面
    BackHandler(enabled = currentScreen != null) {
        currentScreen = null
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Top bar - always present
        TopAppBar(
            title = {
                Text(
                    text =
                        if (currentScreen == null) {
                            "设置"
                        } else {
                            (SettingsItems.getAllItems().find { it.id == currentScreen }?.title ?: "设置详情")
                        },
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                if (currentScreen != null) {
                    IconButton(onClick = { currentScreen = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
        )
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState == null) {
                    // Going back to main screen
                    slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) togetherWith
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                        )
                } else {
                    // Going to detail screen
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) togetherWith
                        slideOutHorizontally(
                            targetOffsetX = { -it },
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                        )
                }
            },
            label = "settings_navigation",
        ) { screen ->
            if (screen == null) {
                // Main settings list
                SettingsMainScreen(
                    onNavigateToDetail = { settingId -> currentScreen = settingId },
                    settingsManager = settingsManager,
                )
            } else {
                // Detail content
                when (screen) {
                    SettingsItems.AUTHENTICATION.id ->
                        AuthenticationSettingsDetail(
                            settingsManager = settingsManager,
                            isServerRunning = isServerRunning,
                        )
                    SettingsItems.SERVER_CONFIG.id ->
                        ServerConfigDetail(
                            settingsManager = settingsManager,
                            isServerRunning = isServerRunning,
                        )
                    SettingsItems.ADVANCED.id ->
                        AdvancedSettingsDetail(
                            settingsManager = settingsManager,
                            isServerRunning = isServerRunning,
                        )
                    SettingsItems.SECURITY.id ->
                        SecuritySettingsDetail(
                            settingsManager = settingsManager,
                            isServerRunning = isServerRunning,
                        )
                    SettingsItems.LOGGING.id ->
                        LoggingSettingsDetail(
                            settingsManager = settingsManager,
                        )
                    SettingsItems.BACKGROUND_SERVICE.id ->
                        BackgroundServiceSettingsDetail(
                            settingsManager = settingsManager,
                        )
                    SettingsItems.NETWORK_DIAGNOSTICS.id ->
                        NetworkDiagnosticsDetail(
                            networkDiagnostics = networkDiagnostics,
                            onRequestLocationPermission = onRequestLocationPermission,
                        )
                    SettingsItems.BACKUP_TOOLS.id ->
                        BackupToolsDetail(
                            settingsManager = settingsManager,
                        )
                    SettingsItems.ABOUT.id -> AboutDetail()
                }
            }
        }
    }
}

/**
 * 主设置列表页面
 */
@Composable
fun SettingsMainScreen(
    onNavigateToDetail: (String) -> Unit,
    settingsManager: SettingsManager,
) {
    val settingsItems = SettingsItems.getAllItems()

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Settings items
        items(settingsItems.size) { index ->
            val item = settingsItems[index]
            SettingsItemCard(
                item = item,
                onClick = {
                    if (item.hasDetails) {
                        onNavigateToDetail(item.id)
                    } else {
                        // Handle items without detail screens (like About)
                        onNavigateToDetail(item.id)
                    }
                },
            )
        }

        // Footer spacing
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 设置项卡片组件
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun SettingsItemCard(
    item: SettingsItem,
    onClick: () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (isPressed) 8.dp else 4.dp,
            ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isPressed) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        interactionSource =
            remember { MutableInteractionSource() }
                .also { interactionSource ->
                    LaunchedEffect(interactionSource) {
                        interactionSource.interactions.collect { interaction ->
                            when (interaction) {
                                is PressInteraction.Press -> isPressed = true
                                is PressInteraction.Release -> isPressed = false
                                is PressInteraction.Cancel -> isPressed = false
                            }
                        }
                    }
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }

            // Arrow indicator
            if (item.hasDetails) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

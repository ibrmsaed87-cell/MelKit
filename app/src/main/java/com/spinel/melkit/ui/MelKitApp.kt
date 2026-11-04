package com.spinel.melkit.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spinel.melkit.BuildConfig
import com.spinel.melkit.R
import com.spinel.melkit.storage.GameLibraryViewModel
import com.spinel.melkit.storage.SavedCopiesViewModel
import com.spinel.melkit.storage.ModVaultViewModel
import com.spinel.melkit.storage.ModProfilesViewModel
import com.spinel.melkit.storage.ModProfilesState
import kotlinx.coroutines.flow.first

private data class Destination(val route: String, val title: Int, val icon: ImageVector)
private data class Feature(val route: String, val title: Int, val description: Int, val icon: ImageVector)

private val destinations = listOf(
    Destination("home", R.string.home, Icons.Outlined.Home),
    Destination("library", R.string.library, Icons.Outlined.Folder),
    Destination("backups", R.string.backups, Icons.Outlined.Archive),
    Destination("share", R.string.sharing, Icons.Outlined.Share),
    Destination("tools", R.string.tools, Icons.Outlined.Build),
)

private val features = listOf(
    Feature("profiles", R.string.profiles, R.string.profiles_description, Icons.Outlined.Layers),
    Feature("toggles", R.string.toggler, R.string.toggler_description, Icons.Outlined.Tune),
    Feature("scanner", R.string.scanner, R.string.scanner_description, Icons.Outlined.Search),
    Feature("bundles", R.string.share_bundle, R.string.bundle_description, Icons.Outlined.Inventory2),
    Feature("categories", R.string.categories, R.string.categories_description, Icons.Outlined.Folder),
    Feature("thumbnails", R.string.thumbnails, R.string.thumbnails_description, Icons.Outlined.Collections),
    Feature("history", R.string.history, R.string.history_description, Icons.Outlined.History),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelKitApp() {
    val libraryModel: GameLibraryViewModel = viewModel()
    val libraryState by libraryModel.state.collectAsState()
    val savedModel: SavedCopiesViewModel = viewModel()
    val savedState by savedModel.state.collectAsState()
    val vaultModel: ModVaultViewModel = viewModel()
    val vaultState by vaultModel.state.collectAsState()
    val profilesModel: ModProfilesViewModel = viewModel()
    val profilesState by profilesModel.state.collectAsState()
    var route by rememberSaveable { mutableStateOf("home") }
    var parent by rememberSaveable { mutableStateOf("home") }
    var toolsTab by rememberSaveable { mutableIntStateOf(0) }
    val root = destinations.any { it.route == route }
    val selectedRoot = if (root) route else parent
    val navigate: (String) -> Unit = { target ->
        if (destinations.any { it.route == route }) parent = route
        route = target
    }
    val title = when (route) {
        "settings" -> R.string.settings
        "shizuku" -> R.string.shizuku_guide
        else -> features.find { it.route == route }?.title ?: R.string.app_name
    }
    BackHandler(enabled = route != "home") {
        route = if (root) "home" else parent
    }

    Scaffold(
        containerColor = MelkitBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (root) Icon(Icons.Outlined.Inventory2, null, tint = MelkitMint)
                        Text(stringResource(title), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    if (!root) IconButton(onClick = {
                        if (route == "profiles" && profilesState.editor != null) {
                            if (!profilesState.busy && !libraryState.busy) profilesModel.closeEditor()
                        } else route = parent
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (route != "settings") IconButton(onClick = { navigate("settings") }) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MelkitBackground),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MelkitSurface) {
                destinations.forEach { item ->
                    NavigationBarItem(
                        selected = selectedRoot == item.route,
                        onClick = { parent = item.route; route = item.route },
                        icon = { Icon(item.icon, null) },
                        label = { Text(stringResource(item.title), fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            key(route) {
                if (route == "library") {
                    LibraryScreen(libraryState, libraryModel) { navigate("shizuku") }
                } else if (route == "backups") {
                    SavedCopiesScreen(savedState, savedModel, libraryModel, libraryState)
                } else if (route == "toggles") {
                    ModVaultScreen(vaultState, vaultModel, libraryModel, libraryState) { navigate("shizuku") }
                } else if (route == "profiles") {
                    ModProfilesScreen(profilesState, profilesModel, libraryModel, libraryState) { navigate("shizuku") }
                } else {
                    Column(
                        Modifier.widthIn(max = 680.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        when (route) {
                            "home" -> HomeScreen(navigate, libraryState.connected, profilesState, profilesModel, libraryModel)
                            "share" -> ShareScreen(navigate)
                            "tools" -> ToolsScreen(toolsTab, { toolsTab = it }, navigate)
                            "settings" -> SettingsScreen()
                            "shizuku" -> ShizukuScreen()
                            else -> features.find { it.route == route }?.let {
                                PlannedScreen(it.title, it.description, it.icon)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(navigate: (String) -> Unit, connected: Boolean, profiles: ModProfilesState,
    profilesModel: ModProfilesViewModel, library: GameLibraryViewModel) {
    LaunchedEffect(connected) {
        if (connected) {
            val ready = library.state.first { !it.busy || !it.connected }
            if (ready.connected) profilesModel.refresh(library)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.dashboard), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        MutedText(R.string.tagline)
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MelkitSurface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.your_library), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth()) {
                LibraryStat(R.string.mods, Icons.Outlined.Inventory2)
                LibraryStat(R.string.maps, Icons.Outlined.Map)
                LibraryStat(R.string.saves, Icons.Outlined.Save)
            }
            MutedText(if (connected) R.string.library_connected else R.string.not_connected)
            FilledTonalButton(onClick = { navigate("library") }) {
                Text(stringResource(R.string.library_browse))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionCard(R.string.backup_action, R.string.backup_subtitle, Icons.Outlined.Archive,
                { navigate("backups") }, Modifier.weight(1f), highlighted = true)
            ActionCard(R.string.transfer_action, R.string.transfer_subtitle, Icons.Outlined.SwapHoriz,
                { navigate("share") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionCard(R.string.profiles, R.string.profiles_subtitle, Icons.Outlined.Layers,
                { navigate("profiles") }, Modifier.weight(1f))
            ActionCard(R.string.scanner, R.string.scanner_subtitle, Icons.Outlined.Search,
                { navigate("scanner") }, Modifier.weight(1f))
        }
    }
    SectionLabel(R.string.current_profile)
    val profileSummary = when {
        !connected -> stringResource(R.string.profile_home_connect)
        profiles.busy -> stringResource(R.string.library_working)
        profiles.recovery -> stringResource(R.string.profile_recovery_body)
        !profiles.loaded || profiles.issue != null -> stringResource(R.string.profile_home_check)
        else -> profiles.items.firstOrNull { it.active }?.name ?: stringResource(R.string.no_profile)
    }
    EmptySummary(R.string.no_profile, R.string.manage, Icons.Outlined.Layers, text = profileSummary) { navigate("profiles") }
    SectionLabel(R.string.last_backup)
    EmptySummary(R.string.no_backup, R.string.view, Icons.Outlined.Archive) { navigate("backups") }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, null, tint = MelkitMint)
            Text(stringResource(R.string.preview_notice), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RowScope.LibraryStat(label: Int, icon: ImageVector) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = MelkitMint, modifier = Modifier.size(28.dp))
        Text(stringResource(R.string.empty_count), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        MutedText(label)
    }
}

@Composable
private fun ActionCard(title: Int, subtitle: Int, icon: ImageVector, onClick: () -> Unit,
    modifier: Modifier = Modifier, highlighted: Boolean = false, enabled: Boolean = true) {
    val color = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minHeight = 148.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (highlighted) MelkitMint else MelkitSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = if (highlighted) color else MelkitMint, modifier = Modifier.size(34.dp))
            Text(stringResource(title), color = color, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(subtitle), color = color.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptySummary(title: Int, action: Int, icon: ImageVector, text: String? = null, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MelkitMint, modifier = Modifier.size(28.dp))
            Text(text ?: stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun PlannedScreen(title: Int, description: Int, icon: ImageVector) {
    Surface(shape = RoundedCornerShape(24.dp), color = MelkitSurface) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MelkitMint, modifier = Modifier.size(48.dp))
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            MutedText(description)
            AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.in_preparation)) })
        }
    }
}

@Composable
private fun ShareScreen(navigate: (String) -> Unit) {
    SectionLabel(R.string.sharing)
    MutedText(R.string.sharing_description)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionCard(R.string.send, R.string.send_hint, Icons.Outlined.Share, {}, Modifier.weight(1f), enabled = false)
        ActionCard(R.string.receive, R.string.receive_hint, Icons.Outlined.SwapHoriz, {}, Modifier.weight(1f), enabled = false)
    }
    Text(stringResource(R.string.in_preparation), color = MaterialTheme.colorScheme.onSurfaceVariant)
    ToolCard(R.string.share_bundle, R.string.bundle_description, Icons.Outlined.Inventory2) { navigate("bundles") }
}

@Composable
private fun ToolsScreen(selected: Int, onSelect: (Int) -> Unit, navigate: (String) -> Unit) {
    TabRow(selectedTabIndex = selected, containerColor = MelkitBackground, contentColor = MelkitMint) {
        listOf(R.string.tools, R.string.more_apps).forEachIndexed { index, text ->
            Tab(selected = selected == index, onClick = { onSelect(index) }, text = { Text(stringResource(text)) })
        }
    }
    if (selected == 0) {
        ToolCard(R.string.shizuku_guide, R.string.shizuku_description, Icons.Outlined.Info) { navigate("shizuku") }
        features.forEach { feature ->
            ToolCard(feature.title, feature.description, feature.icon, planned = feature.route !in listOf("toggles", "profiles")) { navigate(feature.route) }
        }
    } else {
        MoreAppsScreen()
    }
}

@Composable
private fun ToolCard(title: Int, description: Int, icon: ImageVector, planned: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MelkitMint, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                MutedText(description)
                if (planned) Text(stringResource(R.string.in_preparation),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MoreAppsScreen() {
    PromoCard(R.string.mmix_name, R.string.mmix_description, "https://play.google.com/store/apps/details?id=com.spinel.mmix", MelkitMint)
    PromoCard(R.string.mcfx_name, R.string.mcfx_description, "https://play.google.com/store/apps/details?id=com.spinel.mcpackfixer", Color(0xFF86CDFF))
    PromoCard(R.string.mmkb_name, R.string.mmkb_description, null, Color(0xFFFFC987))
    PromoCard(R.string.more_apps, R.string.developer_description, "https://play.google.com/store/apps/dev?id=7189513262046406321", Color(0xFFCAB4FF))
}

@Composable
private fun PromoCard(title: Int, description: Int, url: String?, accent: Color) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface, border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Apps, null, tint = accent, modifier = Modifier.size(28.dp))
                Text(stringResource(title), color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            MutedText(description)
            FilledTonalButton(onClick = { url?.let { openExternal(context, it) } }, enabled = url != null) {
                Text(stringResource(if (url == null) R.string.coming_to_store else R.string.open_store))
            }
        }
    }
}

@Composable
private fun ShizukuScreen() {
    val context = LocalContext.current
    ToolText(R.string.shizuku_first_title, R.string.shizuku_first_body)
    ToolText(R.string.shizuku_later_title, R.string.shizuku_later_body)
    MutedText(R.string.shizuku_video_note)
    FilledTonalButton(onClick = { openExternal(context, "https://shizuku.rikka.app/download/") }) {
        Text(stringResource(R.string.official_download))
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val language = ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language ?: "en"
    SectionLabel(R.string.language)
    MutedText(R.string.language_description)
    Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface) {
        Column {
            listOf("ar" to R.string.language_arabic, "en" to R.string.language_english).forEach { (code, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = language == code,
                        role = Role.RadioButton,
                        onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code)) },
                    ).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = language == code, onClick = null)
                    Text(stringResource(label))
                }
            }
        }
    }
    ToolText(R.string.appearance, R.string.dark_theme)
    Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ToolText(title: Int, body: Int) {
    Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(title)
            MutedText(body)
        }
    }
}

@Composable
private fun SectionLabel(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun MutedText(text: Int) {
    Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_link_error, Toast.LENGTH_SHORT).show()
    }
}



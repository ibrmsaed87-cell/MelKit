package com.spinel.melkit.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spinel.melkit.BuildConfig
import com.spinel.melkit.ads.AdManager
import com.spinel.melkit.share.MelKitShareService
import android.app.Activity
import android.content.ContextWrapper
import com.spinel.melkit.share.ShareViewModel
import com.spinel.melkit.R
import com.spinel.melkit.ShizukuVideoActivity
import com.spinel.melkit.storage.GameLibraryViewModel
import com.spinel.melkit.storage.GameLibraryState
import com.spinel.melkit.storage.SavedCopiesState
import com.spinel.melkit.storage.SavedCopiesViewModel
import com.spinel.melkit.storage.ModVaultViewModel
import com.spinel.melkit.storage.ModProfilesViewModel
import com.spinel.melkit.storage.ModProfilesState
import com.spinel.melkit.storage.FileScanViewModel
import com.spinel.melkit.storage.LibraryOrganizationViewModel
import com.spinel.melkit.storage.LibraryCoversViewModel
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.util.Locale

private data class Destination(val route: String, val title: Int, val icon: ImageVector)
private data class Feature(val route: String, val title: Int, val description: Int, val icon: ImageVector)

private const val MMIX_PACKAGE = "com.spinel.mmix"
private const val MMIX_STORE_URL = "https://play.google.com/store/apps/details?id=$MMIX_PACKAGE"

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
fun MelKitApp(shareRequest: Int = 0) {
    val context = LocalContext.current
    val libraryModel: GameLibraryViewModel = viewModel()
    val libraryState by libraryModel.state.collectAsState()
    val savedModel: SavedCopiesViewModel = viewModel()
    val savedState by savedModel.state.collectAsState()
    val vaultModel: ModVaultViewModel = viewModel()
    val vaultState by vaultModel.state.collectAsState()
    val profilesModel: ModProfilesViewModel = viewModel()
    val profilesState by profilesModel.state.collectAsState()
    val scanModel: FileScanViewModel = viewModel()
    val scanState by scanModel.state.collectAsState()
    val shareModel: ShareViewModel = viewModel()
    val bundleModel: ShareViewModel = viewModel(key = "bundles")
    val sharePage by shareModel.state.collectAsState()
    val bundlePage by bundleModel.state.collectAsState()
    val transfer by MelKitShareService.state.collectAsState()
    val organization: LibraryOrganizationViewModel = viewModel()
    val labels by organization.labels.collectAsState()
    var nestedBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    var route by rememberSaveable { mutableStateOf("home") }
    var parent by rememberSaveable { mutableStateOf("home") }
    var toolsTab by rememberSaveable { mutableIntStateOf(0) }
    val fileWork = libraryState.busy || savedState.busy || vaultState.busy || profilesState.busy || scanState.busy || sharePage.busy || bundlePage.busy
    LaunchedEffect(sharePage.issue, route) {
        if (route == "library" && sharePage.issue != null) Toast.makeText(context,
            shareErrorText(requireNotNull(sharePage.issue), false), Toast.LENGTH_LONG).show()
    }
    val operations by libraryModel.journal.records.collectAsState()
    LaunchedEffect(route, operations, sharePage.savedPath, bundlePage.savedPath) {
        if (route == "home" || route == "backups") savedModel.refresh()
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { libraryModel.refreshConnection(); savedModel.refresh() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val ads = remember(context) { AdManager.get(context) }
    SideEffect {
        val working = libraryState.busy || savedState.busy || vaultState.busy || profilesState.busy ||
            scanState.busy || sharePage.busy || bundlePage.busy || transfer.active
        ads.updateHost(route, allowAppOpen = route == "home" && !working && libraryState.connectionNotice == null,
            allowBreak = !transfer.active && libraryState.connectionNotice == null)
    }
    LaunchedEffect(shareRequest) {
        if (shareRequest > 0) {
            parent = "share"
            route = "share"
        }
    }
    val root = destinations.any { it.route == route }
    val selectedRoot = if (root) route else parent
    LaunchedEffect(route) {
        if (route != "share") shareModel.stopRestore()
    }
    val navigate: (String) -> Unit = { target ->
        val draft = if (route == "bundles") bundlePage else sharePage
        if (target != route && (route == "bundles" || (route == "share" && draft.editing)) && draft.dirty) pendingRoute = target
        else {
            if (destinations.any { it.route == route }) parent = route
            route = target
        }
    }
    val goBack: () -> Unit = {
        if (nestedBack?.invoke() != true) navigate(if (root) "home" else parent)
    }
    val title = when (route) {
        "settings" -> R.string.settings
        "shizuku" -> R.string.shizuku_guide
        else -> features.find { it.route == route }?.title ?: R.string.app_name
    }
    BackHandler(enabled = route != "home") {
        goBack()
    }

    BackHandler(enabled = fileWork) {
        if (bundlePage.batchRestore?.finished == false) bundleModel.stopRestore()
        else Toast.makeText(context, R.string.operation_in_progress, Toast.LENGTH_SHORT).show()
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
                    if (!root) IconButton(enabled = !fileWork, onClick = {
                        if (route == "profiles" && profilesState.editor != null) {
                            if (!profilesState.busy && !libraryState.busy) profilesModel.closeEditor()
                        } else goBack()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (route != "settings") IconButton(enabled = !fileWork, onClick = { navigate("settings") }) {
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
                        enabled = !fileWork, onClick = { navigate(item.route) },
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
                    val covers: LibraryCoversViewModel = viewModel()
                    LibraryScreen(libraryState.copy(busy = libraryState.busy || sharePage.busy), libraryModel, labels, organization, covers,
                        onOrganize = { navigate("categories") }, onShare = { shareModel.shareGame(context, it, libraryModel) }, onGuide = { navigate("shizuku") })
                } else if (route == "backups") {
                    SavedCopiesScreen(savedState, savedModel, libraryModel, libraryState)
                } else if (route == "toggles") {
                    ModVaultScreen(vaultState, vaultModel, libraryModel, libraryState) { navigate("shizuku") }
                } else if (route == "profiles") {
                    ModProfilesScreen(profilesState, profilesModel, libraryModel, libraryState) { navigate("shizuku") }
                } else if (route == "scanner") {
                    FileScanScreen(scanState, scanModel, libraryModel, libraryState) { navigate("shizuku") }
                } else if (route == "share") {
                    DirectShareScreen(shareModel, libraryModel, libraryState, savedModel, savedState, registerBack = { nestedBack = it }) { navigate("shizuku") }
                } else if (route == "bundles") {
                    DirectShareScreen(bundleModel, libraryModel, libraryState, savedModel, savedState, bundles = true, registerBack = { nestedBack = it }) {
                        navigate("shizuku")
                    }
                } else if (route == "categories") {
                    LibraryOrganizationScreen(labels, organization) { navigate("library") }
                } else if (route == "history") {
                    OperationHistoryScreen(libraryModel, libraryState) { navigate("shizuku") }
                } else {
                    Column(
                        Modifier.widthIn(max = 680.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        when (route) {
                            "home" -> HomeScreen(navigate, libraryState, profilesState, profilesModel, libraryModel, savedState, savedModel)
                            "tools" -> ToolsScreen(toolsTab, { toolsTab = it }, navigate)
                            "settings" -> SettingsScreen()
                            "shizuku" -> ShizukuScreen()
                            "thumbnails" -> {
                                Text(stringResource(R.string.cover_tool_help))
                                FilledTonalButton(onClick = { navigate("library") }) {
                                    Text(stringResource(R.string.library_browse))
                                }
                                FilledTonalButton(onClick = { navigate("bundles") }) {
                                    Text(stringResource(R.string.share_bundle))
                                }
                            }
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
    pendingRoute?.let { target ->
        AlertDialog(onDismissRequest = { pendingRoute = null },
            title = { Text(stringResource(R.string.bundle_discard_title)) },
            text = { Text(stringResource(R.string.bundle_discard_body)) },
            confirmButton = { TextButton(onClick = {
                if (route == "bundles") bundleModel.closeBundleSession() else shareModel.closeBundleSession()
                pendingRoute = null
                if (destinations.any { it.route == route }) parent = route
                route = target
            }) { Text(stringResource(R.string.bundle_discard)) } },
            dismissButton = { TextButton(onClick = { pendingRoute = null }) { Text(stringResource(R.string.bundle_keep_editing)) } })
    }
    libraryState.connectionNotice?.let { issue ->
        AlertDialog(onDismissRequest = libraryModel::dismissConnectionNotice,
            title = { Text(stringResource(R.string.library_connect)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(issueText(issue)))
                    TextButton(onClick = { libraryModel.dismissConnectionNotice(); navigate("shizuku") }) {
                        Text(stringResource(R.string.shizuku_guide))
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { libraryModel.dismissConnectionNotice(); openShizuku(context) }) {
                    Text(stringResource(R.string.open_shizuku))
                }
            }, dismissButton = {
                TextButton(onClick = libraryModel::dismissConnectionNotice) { Text(stringResource(R.string.saved_cancel)) }
            })
    }
}

@Composable
private fun HomeScreen(navigate: (String) -> Unit, state: GameLibraryState, profiles: ModProfilesState,
    profilesModel: ModProfilesViewModel, library: GameLibraryViewModel, backups: SavedCopiesState, saved: SavedCopiesViewModel) {
    val context = LocalContext.current
    val connected = state.connected
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(connected, state.busy, state.previewRevision) {
        if (connected && !state.busy) library.refreshCounts()
    }
    DisposableEffect(lifecycleOwner, library) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) library.refreshCounts()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
    MmixCompanionCard()
    Surface(shape = RoundedCornerShape(22.dp), color = MelkitSurface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.your_library), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth()) {
                LibraryStat(R.string.maps, Icons.Outlined.Map, state.counts?.maps, state)
                LibraryStat(R.string.mods, Icons.Outlined.Inventory2, state.counts?.mods, state)
            }
            Row(Modifier.fillMaxWidth()) {
                LibraryStat(R.string.saves, Icons.Outlined.Save, state.counts?.saves, state)
                LibraryStat(R.string.worlds, Icons.Outlined.Public, state.counts?.worlds, state)
            }
            if (connected && state.countsIssue != null) {
                Text(stringResource(R.string.library_counts_failed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = library::refreshCounts, enabled = !state.busy && !state.countsLoading) {
                    Text(stringResource(R.string.library_refresh))
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (connected) R.string.library_connected else R.string.library_connect_title), fontWeight = FontWeight.Bold)
            Text(stringResource(if (connected) R.string.library_ready_body else R.string.library_connect_body))
            FilledTonalButton(enabled = !state.busy, onClick = {
                if (connected) library.browse("") else library.connect()
                navigate("library")
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_browse)) }
            ActionButtons(listOf(
                ActionButton(stringResource(if (connected) R.string.library_refresh else R.string.library_connect),
                    Icons.Outlined.Refresh, enabled = !state.busy, onClick = library::connect),
                ActionButton(stringResource(R.string.open_shizuku), Icons.Outlined.OpenInNew,
                    onClick = { openShizuku(context) }),
                ActionButton(stringResource(R.string.library_help), Icons.Outlined.HelpOutline,
                    onClick = { navigate("shizuku") }),
            ), maxColumns = 3)
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
    val latest = backups.copies.firstOrNull { it.restorable }
    when {
        backups.busy && !backups.loaded -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        backups.issue != null -> EmptySummary(R.string.saved_read_error, R.string.library_refresh, Icons.Outlined.Archive) { saved.refresh() }
        latest == null -> EmptySummary(R.string.no_backup, R.string.backup_action, Icons.Outlined.Archive) {
            if (connected) library.browse("") else library.connect()
            navigate("library")
        }
        else -> {
            val preview by produceState<android.graphics.Bitmap?>(null, latest, backups.revision) { value = saved.preview(latest, backups.revision) }
            Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FileCardHeader(latest.name, preview, Icons.Outlined.Archive, onDetails = {
                        saved.showDetails(latest); navigate("backups")
                    }) {
                        Text(stringResource(contentTypeLabel(latest.path)) + " · " + android.text.format.Formatter.formatFileSize(context, latest.bytes),
                            style = MaterialTheme.typography.bodySmall)
                        val date = if (latest.created > 0) latest.created else latest.modified
                        if (date > 0) Text(stringResource(if (latest.created > 0) R.string.backup_created else R.string.backup_modified,
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                                ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.ENGLISH).format(java.util.Date(date))),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { saved.showDetails(latest); navigate("backups") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.view_backup))
                    }
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, null, tint = MelkitMint)
            Text(stringResource(R.string.preview_notice), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MmixCompanionCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canOpen by remember(context) { mutableStateOf(mmixLaunchIntent(context) != null) }
    DisposableEffect(lifecycleOwner, context) {
        canOpen = mmixLaunchIntent(context) != null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canOpen = mmixLaunchIntent(context) != null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openMmix: () -> Unit = {
        // Recheck on tap in case the installed app changed since the last resume.
        openMmixOrStore(context)
        canOpen = mmixLaunchIntent(context) != null
    }
    Card(onClick = openMmix, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MelkitSurface),
        border = BorderStroke(1.dp, MelkitMint.copy(alpha = 0.6f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Image(painterResource(R.drawable.promo_mmix), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                Text(stringResource(R.string.mmix_name), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_mmix_title), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.home_mmix_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = openMmix) {
                    Text(stringResource(if (canOpen) R.string.home_mmix_open else R.string.home_mmix_download))
                }
            }
        }
    }
}

@Composable
private fun RowScope.LibraryStat(label: Int, icon: ImageVector, count: Int?, state: GameLibraryState) {
    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.ENGLISH
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = MelkitMint, modifier = Modifier.size(28.dp))
        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            when {
                state.countsLoading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                count != null -> Text(NumberFormat.getIntegerInstance(locale).format(count),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                state.connected && state.countsIssue != null -> Text(stringResource(R.string.library_count_unavailable),
                    style = MaterialTheme.typography.labelSmall)
                else -> Text(stringResource(R.string.empty_count), style = MaterialTheme.typography.headlineMedium)
            }
        }
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
private fun ToolsScreen(selected: Int, onSelect: (Int) -> Unit, navigate: (String) -> Unit) {
    TabRow(selectedTabIndex = selected, containerColor = MelkitBackground, contentColor = MelkitMint) {
        listOf(R.string.tools, R.string.more_apps).forEachIndexed { index, text ->
            Tab(selected = selected == index, onClick = { onSelect(index) }, text = { Text(stringResource(text)) })
        }
    }
    if (selected == 0) {
        ToolCard(R.string.shizuku_guide, R.string.shizuku_description, Icons.Outlined.PlayCircle) { navigate("shizuku") }
        features.filterNot { it.route in listOf("categories", "thumbnails") }.forEach { feature ->
            ToolCard(feature.title, feature.description, feature.icon,
                planned = feature.route !in listOf("toggles", "profiles", "scanner", "bundles", "categories", "thumbnails", "history")) {
                navigate(feature.route)
            }
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
    PromoCard(R.string.mmix_name, R.string.mmix_description, MMIX_STORE_URL,
        MelkitMint, R.drawable.promo_mmix)
    PromoCard(R.string.mcfx_name, R.string.mcfx_description, "https://play.google.com/store/apps/details?id=com.spinel.mcpackfixer",
        Color(0xFF86CDFF), R.drawable.promo_mcfx)
    PromoCard(R.string.mmkb_name, R.string.mmkb_description, "https://play.google.com/store/apps/details?id=com.spinel.mcbk",
        Color(0xFFFFC987), R.drawable.promo_mmkb)
    PromoCard(R.string.more_apps, R.string.developer_description, "https://play.google.com/store/apps/dev?id=7189513262046406321",
        Color(0xFFCAB4FF), R.drawable.promo_play)
}

@Composable
private fun PromoCard(title: Int, description: Int, url: String, accent: Color, icon: Int) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface, border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painter = painterResource(icon), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                Text(stringResource(title), color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            MutedText(description)
            FilledTonalButton(onClick = { openExternal(context, url) }) {
                Text(stringResource(R.string.open_store))
            }
        }
    }
}

@Composable
private fun ShizukuScreen() {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MelkitSurface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.PlayCircle, null, tint = MelkitMint, modifier = Modifier.size(30.dp))
                Text(stringResource(R.string.shizuku_video_title),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            MutedText(R.string.shizuku_video_note)
            FilledTonalButton(onClick = { context.startActivity(Intent(context, ShizukuVideoActivity::class.java)) }) {
                Text(stringResource(R.string.shizuku_video_watch))
            }
        }
    }
    ToolText(R.string.shizuku_first_title, R.string.shizuku_first_body)
    ToolText(R.string.shizuku_later_title, R.string.shizuku_later_body)
    FilledTonalButton(onClick = { openExternal(context, "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api") }) {
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
    ToolText(R.string.version, BuildConfig.VERSION_NAME)
    val ads = remember(context) { AdManager.get(context) }
    val privacyRequired by ads.privacyRequired.collectAsState()
    if (privacyRequired) {
        TextButton(onClick = { context.activityOrNull()?.let(ads::showPrivacyOptions) }) {
            Text(stringResource(R.string.ad_privacy_options))
        }
    }
}

@Composable
private fun ToolText(title: Int, body: Int) = ToolText(title, stringResource(body))

@Composable
private fun ToolText(title: Int, body: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MelkitSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(title)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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

private fun mmixLaunchIntent(context: Context): Intent? = try {
    context.packageManager.getLaunchIntentForPackage(MMIX_PACKAGE)
} catch (_: SecurityException) {
    null
}

private fun openMmixOrStore(context: Context) {
    val launch = mmixLaunchIntent(context)
    if (launch != null && startCompanionActivity(context, launch)) return
    val store = Intent(Intent.ACTION_VIEW, Uri.parse(MMIX_STORE_URL)).setPackage("com.android.vending")
    if (startCompanionActivity(context, store)) return
    if (!startCompanionActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(MMIX_STORE_URL)))) {
        Toast.makeText(context, R.string.open_link_error, Toast.LENGTH_SHORT).show()
    }
}

private fun startCompanionActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_link_error, Toast.LENGTH_SHORT).show()
    }
}



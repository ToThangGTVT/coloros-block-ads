package com.utc.color.blockads.feature.settings

import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.common.R as L10n
import android.app.LocaleManager
import android.os.LocaleList
import android.content.Intent
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DnsUpstream.descriptionRes: Int
    get() = when (this) {
        DnsUpstream.System -> L10n.string.dns_system_description
        DnsUpstream.Cloudflare -> L10n.string.dns_cloudflare_description
        DnsUpstream.CloudflareDoH -> L10n.string.dns_cloudflare_doh_description
        DnsUpstream.CloudflareDoT -> L10n.string.dns_cloudflare_dot_description
        DnsUpstream.Quad9 -> L10n.string.dns_quad9_description
        DnsUpstream.AdGuardDoH -> L10n.string.dns_adguard_doh_description
        DnsUpstream.AdGuardDoQ -> L10n.string.dns_adguard_doq_description
        DnsUpstream.Custom -> L10n.string.dns_custom_description
    }

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    engineVersion: String,
    onApps: () -> Unit,
    onCertificate: () -> Unit,
    error: String?,
    settings: SettingsRepository,
    filters: FilterRepository,
    queries: QueryLogRepository,
) {
    val strings = rememberAppStrings()
    val dns by settings.dns.collectAsStateWithLifecycle(initialValue = DnsSettings())
    val blockQuic by settings.blockQuic.collectAsStateWithLifecycle(initialValue = true)
    val autoStart by settings.autoStart.collectAsStateWithLifecycle(initialValue = false)
    val liveUpdates by settings.liveUpdates.collectAsStateWithLifecycle(initialValue = true)
    val ipv6 by settings.ipv6.collectAsStateWithLifecycle(initialValue = true)
    val retention by settings.logRetentionDays.collectAsStateWithLifecycle(initialValue = 7)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val languageTags = listOf("", "vi", "en")
    val appLanguage = localeManager.applicationLocales.let { if (it.isEmpty) "" else it[0].language }
    val notificationManager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    var notificationsAllowed by remember { mutableStateOf(notificationManager.areNotificationsEnabled()) }
    var promotionAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT >= 36 && notificationManager.canPostPromotedNotifications())
    }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var backgroundAllowed by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundAllowed = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                notificationsAllowed = notificationManager.areNotificationsEnabled()
                promotionAllowed = Build.VERSION.SDK_INT >= 36 && notificationManager.canPostPromotedNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var feedback by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = notificationManager.areNotificationsEnabled()
        promotionAllowed = Build.VERSION.SDK_INT >= 36 && notificationManager.canPostPromotedNotifications()
    }
    var customUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var bootstrap by rememberSaveable { mutableStateOf<String?>(null) }
    val batteryPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            backgroundAllowed = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    fun action(success: String? = null, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { feedback = success }
                .onFailure { feedback = it.userMessage(strings) ?: strings.getString(L10n.string.settings_action_failed) }
        }
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                action(strings.getString(L10n.string.configuration_exported)) {
                    withContext(Dispatchers.IO) {
                        val json = settings.exportConfiguration()
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                            it.write(json)
                        } ?: error(strings.getString(L10n.string.file_open_failed))
                    }
                }
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                action(strings.getString(L10n.string.configuration_imported)) {
                    withContext(Dispatchers.IO) {
                        val bytes =
                            context.contentResolver.openInputStream(uri)?.use {
                                it.readNBytes(2 * 1024 * 1024 + 1)
                            } ?: error(strings.getString(L10n.string.file_open_failed))
                        require(bytes.size <= 2 * 1024 * 1024) { strings.getString(L10n.string.file_too_large) }
                        filters.importConfiguration(bytes.toString(Charsets.UTF_8))
                    }
                }
        }
    val updateContent = rememberGitHubUpdateContent()
    AppLazyList {
        item(key = "settings-0", contentType = "settings-section") {
            feedback?.let { AppCard { AppText(it) } }
        }
        item(key = "settings-1", contentType = "settings-section") {
            AppSectionTitle(strings.getString(L10n.string.protection))
        }
        item(key = "settings-2", contentType = "settings-section") {
            AppPreferenceGroup {
                AppSwitchPreference(
                    strings.getString(L10n.string.background_priority),
                    if (backgroundAllowed) strings.getString(L10n.string.battery_exempt)
                    else strings.getString(L10n.string.battery_exempt_hint),
                    backgroundAllowed,
                ) { enabled ->
                    val intent =
                        if (enabled) {
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        } else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    runCatching { batteryPermission.launch(intent) }
                        .onFailure {
                            runCatching {
                                batteryPermission.launch(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                                .onFailure { feedback = strings.getString(L10n.string.battery_settings_failed) }
                        }
                }
                AppSwitchPreference(
                    strings.getString(L10n.string.auto_start),
                    strings.getString(L10n.string.vpn_permission_required),
                    autoStart,
                ) {
                    action { settings.setAutoStart(it) }
                }
                AppSwitchPreference(
                    "IPv6",
                    strings.getString(L10n.string.ipv6_hint),
                    ipv6,
                ) {
                    action { settings.setIpv6(it) }
                }
                AppSwitchPreference(
                    strings.getString(L10n.string.block_quic),
                    strings.getString(L10n.string.block_quic_hint),
                    blockQuic,
                ) {
                    action { settings.setBlockQuic(it) }
                }
                AppArrowPreference(
                    strings.getString(L10n.string.apps),
                    strings.getString(L10n.string.app_filtering_hint),
                    onApps,
                )
                AppArrowPreference(strings.getString(L10n.string.https_filtering), strings.getString(L10n.string.https_filtering_hint), onCertificate)
                AppArrowPreference(strings.getString(L10n.string.always_on_vpn), strings.getString(L10n.string.system_settings)) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                        )
                    }
                        .onFailure { feedback = strings.getString(L10n.string.vpn_settings_failed) }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 36) item(key = "settings-notifications", contentType = "settings-section") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { AppSectionTitle(strings.getString(L10n.string.notifications)) }
                AppHelpTooltip(
                    text = strings.getString(L10n.string.live_updates_help),
                    label = strings.getString(L10n.string.live_updates_help_label),
                )
            }
            AppPreferenceGroup {
                AppSwitchPreference(
                    strings.getString(L10n.string.live_updates),
                    when {
                        liveUpdates && !notificationsAllowed -> strings.getString(L10n.string.notifications_not_allowed)
                        liveUpdates && !promotionAllowed -> strings.getString(L10n.string.live_updates_disabled)
                        else -> null
                    },
                    liveUpdates,
                ) { enabled ->
                    action { settings.setLiveUpdates(enabled) }
                    if (enabled && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (liveUpdates && (!notificationsAllowed || !promotionAllowed)) {
                    AppArrowPreference(strings.getString(L10n.string.android_notification_settings)) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }.onFailure { feedback = strings.getString(L10n.string.notification_settings_failed) }
                    }
                }
            }
        }
        item(key = "settings-3", contentType = "settings-section") {
            AppSectionTitle("DNS")
        }
        item(key = "settings-4", contentType = "settings-section") {
            AppPreferenceGroup {
                AppChoicePreference(
                    strings.getString(L10n.string.dns_upstream),
                    DnsUpstream.entries.map { strings.getString(it.titleRes) },
                    dns.upstream.ordinal,
                    entrySummary = { strings.getString(DnsUpstream.entries[it].descriptionRes) },
                ) { selected ->
                    action { settings.setDns(dns.copy(upstream = DnsUpstream.entries[selected])) }
                }
                if (dns.upstream == DnsUpstream.Custom)
                    AppGroupContent {
                        AppTextInput(
                            customUrl ?: dns.customUrl,
                            strings.getString(L10n.string.dns_url),
                            onChange = { customUrl = it },
                        )
                        AppTextInput(
                            bootstrap ?: dns.customBootstrap,
                            strings.getString(L10n.string.bootstrap_ip),
                            onChange = { bootstrap = it },
                        )
                        AppAction(strings.getString(L10n.string.save), primary = true) {
                            action(strings.getString(L10n.string.custom_dns_saved)) {
                                settings.setDns(
                                    dns.copy(
                                        customUrl = customUrl ?: dns.customUrl,
                                        customBootstrap = bootstrap ?: dns.customBootstrap,
                                    )
                                )
                            }
                        }
                    }
                AppChoicePreference(
                    strings.getString(L10n.string.block_response),
                    listOf("0.0.0.0 / ::", "NXDOMAIN"),
                    dns.blockResponse.ordinal,
                ) { selected ->
                    action {
                        settings.setDns(dns.copy(blockResponse = BlockResponse.entries[selected]))
                    }
                }
                AppSwitchPreference(
                    strings.getString(L10n.string.connection_log),
                    strings.getString(L10n.string.on_device_only),
                    dns.logging,
                ) { enabled ->
                    action { settings.setDns(dns.copy(logging = enabled)) }
                }
            }
        }
        item(key = "settings-5", contentType = "settings-section") {
            AppSectionTitle(strings.getString(L10n.string.appearance))
        }
        item(key = "settings-6", contentType = "settings-section") {
            AppPreferenceGroup {
                AppChoicePreference(
                    title = strings.getString(L10n.string.language),
                    entries = listOf(
                        strings.getString(L10n.string.follow_system),
                        strings.getString(L10n.string.language_vietnamese),
                        strings.getString(L10n.string.language_english),
                    ),
                    selected = languageTags.indexOf(appLanguage).coerceAtLeast(0),
                ) { index ->
                    localeManager.applicationLocales = LocaleList.forLanguageTags(languageTags[index])
                }
                AppChoicePreference(
                    title = strings.getString(L10n.string.theme),
                    entries = listOf(strings.getString(L10n.string.follow_system), strings.getString(L10n.string.theme_light), strings.getString(L10n.string.theme_dark), "Monet"),
                    selected = theme.ordinal,
                    onSelected = { onTheme(ThemeMode.entries[it]) },
                )
            }
        }
        item(key = "settings-7", contentType = "settings-section") {
            if (error != null) AppCard { AppText(error) }
        }
        item(key = "settings-8", contentType = "settings-section") {
            AppSectionTitle(strings.getString(L10n.string.data))
        }
        item(key = "settings-9", contentType = "settings-section") {
            AppPreferenceGroup {
                AppChoicePreference(
                    strings.getString(L10n.string.log_retention),
                    listOf(strings.getString(L10n.string.one_day), strings.getString(L10n.string.seven_days), strings.getString(L10n.string.thirty_days)),
                    listOf(1, 7, 30).indexOf(retention).coerceAtLeast(0),
                ) { index ->
                    action {
                        settings.setLogRetentionDays(listOf(1, 7, 30)[index])
                        queries.prune()
                    }
                }
                AppArrowPreference(strings.getString(L10n.string.export_configuration), strings.getString(L10n.string.json_file)) {
                    export.launch("blockads-config.json")
                }
                AppArrowPreference(strings.getString(L10n.string.import_configuration), strings.getString(L10n.string.import_json_hint)) {
                    import.launch(arrayOf("application/json", "text/plain"))
                }
                AppArrowPreference(strings.getString(L10n.string.clear_logs)) {
                    action(strings.getString(L10n.string.logs_cleared)) { queries.clearLogs() }
                }
            }
        }
        item(key = "settings-10", contentType = "settings-section") {
            updateContent()
        }
        item(key = "settings-11", contentType = "settings-section") {
            AppSectionTitle(strings.getString(L10n.string.about))
        }
        item(key = "settings-12", contentType = "settings-section") {
            AppCard {
                AppText(
                    "${strings.getString(L10n.string.app_display_name)} ${remember(context) { com.utc.color.blockads.core.data.GitHubUpdateRepository(context).currentVersion }}",
                    prominent = true,
                )
                AppHint("Rust JNI · $engineVersion")
                AppHint("COUI 1.1.0 · ColorOS 16")
            }
        }
    }
}

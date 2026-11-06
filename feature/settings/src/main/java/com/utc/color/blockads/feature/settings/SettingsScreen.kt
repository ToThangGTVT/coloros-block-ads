package com.utc.color.blockads.feature.settings

import android.Manifest
import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.utc.color.blockads.core.common.BlockResponse
import com.utc.color.blockads.core.common.DnsSettings
import com.utc.color.blockads.core.common.DnsUpstream
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.PreferenceRow
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.choiceSheet
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.core.filter.FilterRepository
import com.utc.color.blockads.feature.settings.databinding.ScreenSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import com.utc.color.blockads.core.common.R as Strings

class SettingsScreen private constructor(
    activity: ComponentActivity,
    private val views: ScreenSettingsBinding,
    state: Bundle,
    theme: StateFlow<ThemeMode>,
    onTheme: (ThemeMode) -> Unit,
    engineVersion: StateFlow<String>,
    error: StateFlow<String?>,
    private val settings: SettingsRepository,
    private val filters: FilterRepository,
    private val queries: QueryLogRepository,
    onApps: () -> Unit,
    onCertificate: () -> Unit,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, theme: StateFlow<ThemeMode>, onTheme: (ThemeMode) -> Unit,
        engineVersion: StateFlow<String>, error: StateFlow<String?>, settings: SettingsRepository,
        filters: FilterRepository, queries: QueryLogRepository, onApps: () -> Unit, onCertificate: () -> Unit,
    ) : this(
        activity, ScreenSettingsBinding.inflate(parent.inflater, parent, false), state, theme, onTheme, engineVersion,
        error, settings, filters, queries, onApps, onCertificate,
    )

    private val locale = context.getSystemService(LocaleManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var live = true
    private var dns = DnsSettings()
    private var loadedDns = false
    private val draftUrl = state.getString("customDnsUrl")
    private val draftBootstrap = state.getString("customDnsBootstrap")
    private val notificationPermission = result("settings.notification", ActivityResultContracts.RequestPermission()) {
        renderNotifications()
    }
    private val batteryPermission = result("settings.battery", ActivityResultContracts.StartActivityForResult()) {
        renderBattery()
    }
    private val export = result("settings.export", ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform(Strings.string.configuration_exported) {
            withContext(Dispatchers.IO) {
                val json = settings.exportConfiguration()
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    ?: error(context.getString(Strings.string.file_open_failed))
            }
        }
    }
    private val importConfig = result("settings.import", ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform(Strings.string.configuration_imported) {
            withContext(Dispatchers.IO) {
                val limit = 2 * 1024 * 1024
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(limit + 1) }
                    ?: error(context.getString(Strings.string.file_open_failed))
                require(bytes.size <= limit) { context.getString(Strings.string.file_too_large) }
                filters.importConfiguration(bytes.toString(Charsets.UTF_8))
            }
        }
    }

    init {
        views.apps.root.bind(context.getString(Strings.string.apps), context.getString(Strings.string.app_filtering_hint),
            trailingIcon = PreferenceIcon.Arrow, action = onApps)
        views.httpsFiltering.root.bind(context.getString(Strings.string.https_filtering),
            context.getString(Strings.string.https_filtering_hint), trailingIcon = PreferenceIcon.Arrow, action = onCertificate)
        views.alwaysOn.root.bind(context.getString(Strings.string.always_on_vpn),
            context.getString(Strings.string.system_settings), trailingIcon = PreferenceIcon.Arrow) {
            open(Intent(Settings.ACTION_VPN_SETTINGS), Strings.string.vpn_settings_failed)
        }
        views.notifications.isVisible = Build.VERSION.SDK_INT >= 36
        views.notificationHelp.root.bind(context.getString(Strings.string.live_updates_help_label), trailingIcon = PreferenceIcon.Info) {
            showHelp(views.notificationHelp.root.trailingIconView, Strings.string.live_updates_help)
        }
        views.dnsUrl.root.hint = context.getString(Strings.string.dns_url)
        views.dnsUrl.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        views.bootstrapIp.root.hint = context.getString(Strings.string.bootstrap_ip)
        views.bootstrapIp.input.setSingleLine()
        views.saveDns.setOnClickListener {
            perform(Strings.string.custom_dns_saved) {
                settings.setDns(dns.copy(
                    customUrl = views.dnsUrl.input.text.toString(),
                    customBootstrap = views.bootstrapIp.input.text.toString(),
                ))
            }
        }
        val languageTags = listOf("", "vi", "en")
        val locales = locale.applicationLocales
        val selected = if (locales.isEmpty) 0 else languageTags.indexOf(locales[0].language).coerceAtLeast(0)
        choose(views.language.root, Strings.string.language, labels(
            Strings.string.follow_system, Strings.string.language_vietnamese, Strings.string.language_english,
        ), selected) { locale.applicationLocales = LocaleList.forLanguageTags(languageTags[it]) }
        views.exportConfiguration.root.bind(context.getString(Strings.string.export_configuration),
            context.getString(Strings.string.json_file), trailingIcon = PreferenceIcon.Arrow) { export.launch("blockads-config.json") }
        views.importConfiguration.root.bind(context.getString(Strings.string.import_configuration),
            context.getString(Strings.string.import_json_hint), trailingIcon = PreferenceIcon.Arrow) {
            importConfig.launch(arrayOf("application/json", "text/plain"))
        }
        views.clearLogs.root.bind(context.getString(Strings.string.clear_logs), trailingIcon = PreferenceIcon.Arrow) {
            perform(Strings.string.logs_cleared) { queries.clearLogs() }
        }
        val updates = GitHubUpdateSection(context, views, scope)
        val installPermission = result("settings.install", ActivityResultContracts.StartActivityForResult()) {
            updates.onPermissionResult()
        }
        updates.bind(installPermission::launch)
        views.version.text = "${context.getString(Strings.string.app_display_name)} ${updates.version}"
        onResume {
            renderBattery()
            renderNotifications()
        }
        observe(settings.autoStart) {
            toggle(views.autoStart.root, context.getString(Strings.string.auto_start), Strings.string.vpn_permission_required, it) {
                settings.setAutoStart(!it)
            }
        }
        observe(settings.ipv6) {
            toggle(views.ipv6.root, "IPv6", Strings.string.ipv6_hint, it) { settings.setIpv6(!it) }
        }
        observe(settings.blockQuic) {
            toggle(views.blockQuic.root, context.getString(Strings.string.block_quic), Strings.string.block_quic_hint, it) {
                settings.setBlockQuic(!it)
            }
        }
        observe(settings.liveUpdates) {
            live = it
            renderNotifications()
        }
        observe(settings.dns) { renderDns(it) }
        observe(settings.logRetentionDays) { days ->
            val options = listOf(1, 7, 30)
            choose(views.retention.root, Strings.string.log_retention,
                labels(Strings.string.one_day, Strings.string.seven_days, Strings.string.thirty_days),
                options.indexOf(days).coerceAtLeast(0),
            ) { index ->
                perform {
                    settings.setLogRetentionDays(options[index])
                    queries.prune()
                }
            }
        }
        observe(theme) {
            choose(views.theme.root, Strings.string.theme,
                labels(Strings.string.follow_system, Strings.string.theme_light, Strings.string.theme_dark) + "Monet",
                it.ordinal,
            ) { index -> onTheme(ThemeMode.entries[index]) }
        }
        observe(engineVersion) { views.engineVersion.text = "Rust JNI · $it" }
        observe(error) { views.themeError.showMessage(it) }
    }

    private fun renderDns(value: DnsSettings) {
        if (!loadedDns) {
            views.dnsUrl.input.setText(draftUrl ?: value.customUrl)
            views.bootstrapIp.input.setText(draftBootstrap ?: value.customBootstrap)
        } else {
            if (value.customUrl != dns.customUrl) views.dnsUrl.input.setText(value.customUrl)
            if (value.customBootstrap != dns.customBootstrap) views.bootstrapIp.input.setText(value.customBootstrap)
        }
        loadedDns = true
        dns = value
        views.customDns.isVisible = value.upstream == DnsUpstream.Custom
        val upstreams = DnsUpstream.entries
        choose(views.upstream.root, Strings.string.dns_upstream,
            upstreams.map { context.getString(it.titleRes) }, value.upstream.ordinal,
            { context.getString(upstreams[it].descriptionRes) },
        ) { index -> perform { settings.setDns(dns.copy(upstream = upstreams[index])) } }
        choose(views.blockResponse.root, Strings.string.block_response, listOf("0.0.0.0 / ::", "NXDOMAIN"),
            value.blockResponse.ordinal,
        ) { index -> perform { settings.setDns(dns.copy(blockResponse = BlockResponse.entries[index])) } }
        toggle(views.connectionLog.root, context.getString(Strings.string.connection_log),
            Strings.string.on_device_only, value.logging,
        ) { settings.setDns(dns.copy(logging = !value.logging)) }
    }

    private fun renderBattery() {
        val allowed = power.isIgnoringBatteryOptimizations(context.packageName)
        views.backgroundPriority.root.bind(
            context.getString(Strings.string.background_priority),
            context.getString(if (allowed) Strings.string.battery_exempt else Strings.string.battery_exempt_hint),
            checked = allowed,
        ) {
            val packageUri = Uri.parse("package:${context.packageName}")
            val intent = if (allowed) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            runCatching { batteryPermission.launch(intent) }.onFailure {
                runCatching {
                    batteryPermission.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                }.onFailure { views.feedback.showMessage(context.getString(Strings.string.battery_settings_failed)) }
            }
        }
    }

    private fun renderNotifications() {
        if (Build.VERSION.SDK_INT < 36) return
        val summary = when {
            live && !notifications.areNotificationsEnabled() -> Strings.string.notifications_not_allowed
            live && !notifications.canPostPromotedNotifications() -> Strings.string.live_updates_disabled
            else -> null
        }
        views.liveUpdates.root.bind(context.getString(Strings.string.live_updates), summary?.let(context::getString), checked = live) {
            val enable = !live
            perform { settings.setLiveUpdates(enable) }
            if (enable && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        views.notificationSettings.root.isVisible = summary != null
        views.notificationSettings.root.bind(context.getString(Strings.string.android_notification_settings), trailingIcon = PreferenceIcon.Arrow) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                Strings.string.notification_settings_failed)
        }
    }

    private fun labels(vararg ids: Int) = ids.map(context::getString)

    private fun choose(
        row: PreferenceRow, label: Int, options: List<String>, index: Int,
        summary: (Int) -> String? = { null }, select: (Int) -> Unit,
    ) {
        val title = context.getString(label)
        row.bind(title, options[index], trailingIcon = PreferenceIcon.Dropdown) {
            show(choiceSheet(context, title, options, index, summary, select))
        }
    }

    private fun toggle(row: PreferenceRow, label: String, summary: Int, checked: Boolean, update: suspend () -> Unit) {
        row.bind(label, context.getString(summary), checked = checked) { perform(block = update) }
    }

    private fun perform(message: Int? = null, block: suspend () -> Unit) {
        action({ views.feedback.showMessage(message?.let(context::getString)) }, {
            views.feedback.showMessage(it.userMessage(resources) ?: context.getString(Strings.string.settings_action_failed))
        }, block)
    }

    override fun saveState() = super.saveState().apply {
        val url = if (loadedDns) views.dnsUrl.input.text.toString().takeIf { it != dns.customUrl } else draftUrl
        val bootstrap = if (loadedDns) views.bootstrapIp.input.text.toString().takeIf { it != dns.customBootstrap } else draftBootstrap
        putString("customDnsUrl", url)
        putString("customDnsBootstrap", bootstrap)
    }

    private fun open(intent: Intent, error: Int) {
        runCatching { context.startActivity(intent) }.onFailure { views.feedback.showMessage(context.getString(error)) }
    }
}

private val DnsUpstream.descriptionRes: Int
    get() = when (this) {
        DnsUpstream.System -> Strings.string.dns_system_description
        DnsUpstream.Cloudflare -> Strings.string.dns_cloudflare_description
        DnsUpstream.CloudflareDoH -> Strings.string.dns_cloudflare_doh_description
        DnsUpstream.CloudflareDoT -> Strings.string.dns_cloudflare_dot_description
        DnsUpstream.Quad9 -> Strings.string.dns_quad9_description
        DnsUpstream.AdGuardDoH -> Strings.string.dns_adguard_doh_description
        DnsUpstream.AdGuardDoQ -> Strings.string.dns_adguard_doq_description
        DnsUpstream.Custom -> Strings.string.dns_custom_description
    }

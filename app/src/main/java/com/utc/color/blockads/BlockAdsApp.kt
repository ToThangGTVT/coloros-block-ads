package com.utc.color.blockads

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.common.TunnelState
import com.utc.color.blockads.core.designsystem.AppNavDisplay
import com.utc.color.blockads.core.designsystem.AppPage
import com.utc.color.blockads.core.designsystem.AppTab
import com.utc.color.blockads.core.designsystem.AppTabHost
import com.utc.color.blockads.core.designsystem.appTabMetadata
import com.utc.color.blockads.core.filter.CatalogSource
import com.utc.color.blockads.feature.apps.AppsScreen
import com.utc.color.blockads.feature.certificate.CertificateScreen
import com.utc.color.blockads.feature.filters.FilterCatalogScreen
import com.utc.color.blockads.feature.filters.FiltersScreen
import com.utc.color.blockads.feature.home.HomeScreen
import com.utc.color.blockads.feature.querylog.QueryLogScreen
import com.utc.color.blockads.feature.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
private enum class Destination : NavKey {
    Home,
    Log,
    Filters,
    Firewall,
    Settings,
    Apps,
    Certificate,
    Catalog,
    BrowserCatalog,
}

private val Destination.tab
    get() =
        when (this) {
            Destination.Home -> AppTab.Home
            Destination.Log -> AppTab.Log
            Destination.Firewall -> AppTab.Firewall
            Destination.Filters,
            Destination.Catalog,
            Destination.BrowserCatalog -> AppTab.Filters
            else -> AppTab.Settings
        }

private val Destination.isTab
    get() =
        this in
            setOf(
                Destination.Home,
                Destination.Log,
                Destination.Filters,
                Destination.Firewall,
                Destination.Settings,
            )

@Composable
fun BlockAdsApp(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    version: String,
    error: String?,
    tunnel: TunnelState,
    filterStatus: com.utc.color.blockads.core.common.FilterStatus,
    settings: com.utc.color.blockads.core.data.SettingsRepository,
    filters: com.utc.color.blockads.core.filter.FilterRepository,
    queries: com.utc.color.blockads.core.data.QueryLogRepository,
    apps: com.utc.color.blockads.core.data.AppsRepository,
    certificates: com.utc.color.blockads.core.certificate.CertificateRepository,
    onProtection: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(Destination.Home)
    val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    val selectTab: (AppTab) -> Unit = { tab ->
        val target = Destination.valueOf(tab.name)
        // Home remains the root so a back gesture from another tab returns home.
        if (backStack.last() != target) {
            backStack.clear()
            backStack.add(Destination.Home)
            if (target != Destination.Home) backStack.add(target)
        }
    }
    val current = backStack.last() as Destination
    AppTabHost(selectedTab = current.tab, onTab = selectTab, showBar = current.isTab) {
        AppNavDisplay(
            backStack = backStack,
            onBack = pop,
            entryProvider =
                entryProvider {
                    entry<Destination>(
                        metadata = { destination ->
                            if (destination.isTab) appTabMetadata(destination.tab) else emptyMap()
                        }
                    ) { destination ->
                        val title =
                            when (destination) {
                                Destination.Apps -> "Ứng dụng"
                                Destination.Certificate -> "Lọc HTTPS"
                                Destination.Catalog -> "Danh sách DNS"
                                Destination.BrowserCatalog -> "Bộ lọc trình duyệt"
                                else -> destination.tab.label
                            }
                        AppPage(
                            title = title,
                            scrollable =
                                destination != Destination.Log &&
                                    destination != Destination.Apps &&
                                    destination != Destination.Firewall &&
                                    destination != Destination.Catalog &&
                                    destination != Destination.BrowserCatalog &&
                                    destination != Destination.Filters &&
                                    destination != Destination.Settings,
                            onBack =
                                if (
                                    destination == Destination.Apps ||
                                        destination == Destination.Certificate ||
                                        destination == Destination.Catalog ||
                                        destination == Destination.BrowserCatalog
                                )
                                    pop
                                else null,
                        ) {
                            when (destination) {
                                Destination.Home ->
                                    HomeScreen(
                                        tunnel,
                                        filterStatus,
                                        queries,
                                        onPrivateDns = {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.provider.Settings
                                                        .ACTION_WIRELESS_SETTINGS
                                                )
                                            )
                                        },
                                        onProtection,
                                    )
                                Destination.Log -> QueryLogScreen(queries, filters, settings, apps)
                                Destination.Filters ->
                                    FiltersScreen(
                                        filters,
                                        settings,
                                        onCatalog = { backStack.add(Destination.Catalog) },
                                        onBrowserCatalog = {
                                            backStack.add(Destination.BrowserCatalog)
                                        },
                                    )
                                Destination.Catalog ->
                                    FilterCatalogScreen(filters, CatalogSource.Dns)
                                Destination.BrowserCatalog ->
                                    FilterCatalogScreen(filters, CatalogSource.Browser)
                                Destination.Settings ->
                                    SettingsScreen(
                                        theme,
                                        onTheme,
                                        engineVersion = version,
                                        onApps = { backStack.add(Destination.Apps) },
                                        onCertificate = { backStack.add(Destination.Certificate) },
                                        error = error,
                                        settings = settings,
                                        filters = filters,
                                        queries = queries,
                                    )
                                Destination.Apps -> AppsScreen(apps, settings)
                                Destination.Firewall ->
                                    AppsScreen(apps, settings, firewallOnly = true)
                                Destination.Certificate ->
                                    CertificateScreen(
                                        certificates,
                                        settings,
                                        apps,
                                        onApps = { backStack.add(Destination.Apps) },
                                    )
                            }
                        }
                    }
                },
        )
    }
}

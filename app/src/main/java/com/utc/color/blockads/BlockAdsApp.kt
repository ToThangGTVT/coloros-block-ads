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
import com.utc.color.blockads.feature.apps.AppsScreen
import com.utc.color.blockads.feature.certificate.CertificateScreen
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
    Settings,
    Apps,
    Certificate,
}

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
    AppNavDisplay(
        backStack = backStack,
        onBack = pop,
        entryProvider =
            entryProvider {
                entry<Destination> { destination ->
                    val selected =
                        when (destination) {
                            Destination.Home -> AppTab.Home
                            Destination.Log -> AppTab.Log
                            Destination.Filters -> AppTab.Filters
                            else -> AppTab.Settings
                        }
                    val title =
                        when (destination) {
                            Destination.Apps -> "Ứng dụng"
                            Destination.Certificate -> "Chứng chỉ HTTPS"
                            else -> selected.label
                        }
                    AppPage(
                        title = title,
                        selectedTab = selected,
                        onTab = selectTab,
                        scrollable =
                            destination != Destination.Log && destination != Destination.Apps,
                        onBack =
                            if (
                                destination == Destination.Apps ||
                                    destination == Destination.Certificate
                            )
                                pop
                            else null,
                    ) {
                        when (destination) {
                            Destination.Home ->
                                HomeScreen(
                                    version,
                                    tunnel,
                                    filterStatus,
                                    queries,
                                    onPrivateDns = {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_WIRELESS_SETTINGS
                                            )
                                        )
                                    },
                                    onProtection,
                                )
                            Destination.Log -> QueryLogScreen(queries, filters, settings, apps)
                            Destination.Filters -> FiltersScreen(filters, settings)
                            Destination.Settings ->
                                SettingsScreen(
                                    theme,
                                    onTheme,
                                    onApps = { backStack.add(Destination.Apps) },
                                    onCertificate = { backStack.add(Destination.Certificate) },
                                    error = error,
                                    settings = settings,
                                    filters = filters,
                                    queries = queries,
                                )
                            Destination.Apps -> AppsScreen(apps, settings)
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

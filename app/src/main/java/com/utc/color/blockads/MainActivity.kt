package com.utc.color.blockads

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.utc.color.blockads.core.common.ThemeMode
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.color
import com.utc.color.blockads.core.filter.CatalogSource
import com.utc.color.blockads.databinding.ActivityMainBinding
import com.utc.color.blockads.feature.apps.AppsScreen
import com.utc.color.blockads.feature.certificate.CertificateScreen
import com.utc.color.blockads.feature.filters.FilterCatalogScreen
import com.utc.color.blockads.feature.filters.FiltersScreen
import com.utc.color.blockads.feature.home.HomeScreen
import com.utc.color.blockads.feature.querylog.AppRequestsScreen
import com.utc.color.blockads.feature.querylog.QueryLogScreen
import com.utc.color.blockads.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.utc.color.blockads.core.common.R as Strings
import com.utc.color.blockads.core.designsystem.R as Design

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var back: OnBackPressedCallback
    private var screen: Screen? = null
    private var themeMode = ThemeMode.System
    private val stack = mutableListOf(Page(Destination.Home))

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.tunnel.start()
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { prepareVpn() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.let {
            themeMode = ThemeMode.entries[it.getInt("theme")]
            BundleCompat.getParcelableArrayList(it, "pages", Bundle::class.java)
                ?.takeIf { pages -> pages.isNotEmpty() }?.let { pages ->
                    stack.clear()
                    stack.addAll(pages.map(::restorePage))
                }
        }
        back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = pop()
        }
        onBackPressedDispatcher.addCallback(this, back)
        inflateShell()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.theme.collect { mode ->
                    if (mode != themeMode) {
                        closeScreen()
                        themeMode = mode
                        inflateShell()
                    }
                }
            }
        }
    }

    private fun themedContext(): Context {
        val systemMode = resources.configuration.uiMode
        val night = when (themeMode) {
            ThemeMode.Light -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.Dark -> Configuration.UI_MODE_NIGHT_YES
            else -> systemMode and Configuration.UI_MODE_NIGHT_MASK
        }
        val style = if (themeMode == ThemeMode.Monet) Design.style.Theme_BlockAds_Views_Monet else R.style.Theme_BlockAds
        return ContextThemeWrapper(this, style).apply {
            applyOverrideConfiguration(Configuration().apply {
                fontScale = 0f
                uiMode = (systemMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
        }
    }

    private fun inflateShell() {
        val context = themedContext()
        binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
        window.setBackgroundDrawable(context.color(android.R.attr.colorBackground).toDrawable())
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.navigation.isVisible = stack.last().destination.tabId != 0 && keyboard.bottom == 0
            view.updatePadding(
                left = bars.left,
                right = bars.right,
                top = if (stack.last().destination == Destination.Home) 0 else bars.top,
                bottom = if (binding.navigation.isVisible) 0 else maxOf(bars.bottom, keyboard.bottom),
            )
            insets
        }
        renderPage()
        binding.navigation.setOnItemSelectedListener { item ->
            val destination = Destination.entries.first { it.tabId == item.itemId }
            selectTab(destination)
            true
        }
    }

    private fun renderPage() {
        val page = stack.last()
        val destination = page.destination
        back.isEnabled = stack.size > 1
        binding.toolbar.isVisible = destination != Destination.Home
        binding.toolbar.title = page.label ?: getString(destination.title)
        binding.toolbar.navigationIcon =
            if (destination.tabId == 0) AppCompatResources.getDrawable(binding.root.context, Design.drawable.ic_back) else null
        binding.toolbar.setNavigationIconTint(binding.root.context.color(com.google.android.material.R.attr.colorOnSurface))
        binding.toolbar.setNavigationOnClickListener { pop() }
        binding.navigation.isVisible = destination.tabId != 0
        if (destination.tabId != 0) binding.navigation.menu.findItem(destination.tabId).isChecked = true
        val dark = binding.root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark && destination != Destination.Home
            isAppearanceLightNavigationBars = !dark
        }

        val host = binding.screenHost
        val state = page.state
        val vm = viewModel
        screen = when (destination) {
            Destination.Home -> HomeScreen(
                this, host, state, vm.tunnel.state, vm.filters.status, vm.queries, vm.settings,
                vm.certificates, ::setProtection, { push(Destination.Certificate) },
                { selectTab(Destination.Settings) },
                { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) },
            )
            Destination.Log -> QueryLogScreen(this, host, state, vm.queries, vm.filters, vm.settings, vm.apps) { uid, label ->
                push(Destination.AppRequests, uid, label)
            }
            Destination.AppRequests -> AppRequestsScreen(this, host, state, vm.queries, vm.filters, vm.settings, vm.apps, page.uid)
            Destination.Filters -> FiltersScreen(this, host, state, vm.filters, vm.settings) {
                push(if (it) Destination.BrowserCatalog else Destination.Catalog)
            }
            Destination.Catalog, Destination.BrowserCatalog -> FilterCatalogScreen(
                this, host, state, vm.filters,
                if (destination == Destination.Catalog) CatalogSource.Dns else CatalogSource.Browser,
            )
            Destination.Firewall, Destination.Apps -> AppsScreen(this, host, state, vm.apps, vm.settings, destination == Destination.Firewall)
            Destination.Certificate -> CertificateScreen(this, host, state, vm.certificates, vm.settings, vm.apps) { push(Destination.Apps) }
            Destination.Settings -> SettingsScreen(
                this, host, state, vm.theme, vm::setTheme, vm.version, vm.error, vm.settings, vm.filters, vm.queries,
                { push(Destination.Apps) }, { push(Destination.Certificate) },
            )
        }.also {
            host.addView(it.view)
            it.restoreViews(state)
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun selectTab(destination: Destination) {
        if (stack.last().destination == destination) return
        closeScreen()
        stack.subList(1, stack.size).clear()
        if (destination != Destination.Home) stack.add(Page(destination))
        renderPage()
    }

    private fun push(destination: Destination, uid: Int? = null, label: String? = null) {
        closeScreen()
        stack.add(Page(destination, uid, label))
        renderPage()
    }

    private fun pop() {
        if (stack.size == 1) return
        closeScreen()
        stack.removeAt(stack.lastIndex)
        renderPage()
    }

    private fun closeScreen() {
        screen?.let {
            stack.last().state = it.saveState()
            it.close()
            binding.screenHost.removeAllViews()
        }
        screen = null
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { viewModel.certificates.refresh() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        screen?.let { stack.last().state = it.saveState() }
        outState.putInt("theme", themeMode.ordinal)
        outState.putParcelableArrayList("pages", ArrayList(stack.map(Page::toBundle)))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        screen?.close()
        super.onDestroy()
    }

    private fun prepareVpn() {
        val consent = viewModel.tunnel.prepare()
        if (consent == null) viewModel.tunnel.start() else vpnPermission.launch(consent)
    }

    private fun setProtection(enabled: Boolean) {
        if (!enabled) {
            viewModel.tunnel.stop()
        } else if (
            Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            prepareVpn()
        }
    }
}

private enum class Destination(val title: Int, val tabId: Int = 0) {
    Home(Strings.string.home, R.id.home),
    Log(Strings.string.log, R.id.log),
    Filters(Strings.string.filters, R.id.filters),
    Firewall(Strings.string.firewall, R.id.firewall),
    Settings(Strings.string.settings, R.id.settings),
    Apps(Strings.string.apps),
    Certificate(Strings.string.https_filtering),
    Catalog(Strings.string.dns_lists),
    BrowserCatalog(Strings.string.browser_filters),
    AppRequests(Strings.string.queries),
}

private data class Page(
    val destination: Destination,
    val uid: Int? = null,
    val label: String? = null,
    var state: Bundle = Bundle(),
) {
    fun toBundle() = Bundle().apply {
        putString("destination", destination.name)
        uid?.let { putInt("uid", it) }
        putString("label", label)
        putBundle("state", state)
    }
}

private fun restorePage(bundle: Bundle) = Page(
    Destination.valueOf(requireNotNull(bundle.getString("destination"))),
    bundle.getInt("uid").takeIf { bundle.containsKey("uid") },
    bundle.getString("label"),
    bundle.getBundle("state") ?: Bundle(),
)

package com.utc.color.blockads.core.data

import android.Manifest
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.utc.color.blockads.core.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Package metadata and icons are loaded off main; never inspect app data or payloads. */
class AppsRepository(context: Context, private val settings: SettingsRepository) {
    private val context = context.applicationContext
    private val manager = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps = _apps.asStateFlow()
    val policies = settings.appPolicies.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val blockedApps = settings.blockedApps.stateIn(scope, SharingStarted.Eagerly, emptySet())
    private val cache =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    cache.evictAll()
                    refresh()
                }
            },
            filter,
            Context.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    fun refresh() {
        scope.launch {
            _apps.value =
                manager
                    .getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                    .filter {
                        manager.checkPermission(Manifest.permission.INTERNET, it.packageName) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    .map {
                        InstalledApp(
                            it.packageName,
                            it.uid,
                            it.loadLabel(manager).toString(),
                            it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        )
                    }
                    .sortedWith(
                        compareBy<InstalledApp> { it.system }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    )
        }
    }

    suspend fun setMode(app: InstalledApp, mode: AppFilterMode) {
        // Android routing is UID-based. All packages sharing this UID must use one policy.
        settings.setAppMode(apps.value.filter { it.uid == app.uid }.map { it.packageName }, mode)
    }

    fun forUid(uid: Int?) = apps.value.filter { it.uid == uid }

    fun cachedIcon(packageName: String): Bitmap? = cache.get(packageName)

    suspend fun setNetworkAllowed(app: InstalledApp, allowed: Boolean) {
        val packages = apps.value.filter { it.uid == app.uid }.map { it.packageName }
        settings.setNetworkAllowed((packages + app.packageName).distinct(), allowed)
    }

    suspend fun icon(packageName: String): Bitmap? =
        withContext(Dispatchers.IO) {
            cache.get(packageName)
                ?: runCatching {
                    val size =
                        (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 144)
                    val drawable = manager.getApplicationIcon(packageName)
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                        drawable.setBounds(0, 0, size, size)
                        drawable.draw(Canvas(bitmap))
                        cache.put(packageName, bitmap)
                    }
                }
                    .getOrNull()
        }
}

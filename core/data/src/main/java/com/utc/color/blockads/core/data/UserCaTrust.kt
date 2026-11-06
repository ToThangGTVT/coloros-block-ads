package com.utc.color.blockads.core.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser

/**
 * Whether an app accepts certificates from Android's user CA store, which HTTPS filtering needs.
 *
 * Since API 24 apps trust only system CAs unless their network security config adds
 * `<certificates src="user"/>`. Intercepting an app that does not would show a certificate error
 * for every new host (e.g. Google Go's WebView), so such apps can never be HTTPS-filtered.
 */
object UserCaTrust {
    private const val Android = "http://schemas.android.com/apk/res/android"

    /** Gecko browsers verify with their own store and can opt in to Android user CAs. */
    private val OwnTrustStore =
        setOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.fennec_fdroid",
        )

    /** Keyed by APK path, which changes on every update. */
    private val cache = ConcurrentHashMap<String, Pair<String, Boolean>>()

    /** Unreadable configs count as trusting, so a parser gap never blocks a working app. */
    fun trusts(context: Context, packageName: String): Boolean {
        if (packageName in OwnTrustStore) return true
        val info =
            runCatching {
                    context.packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0),
                    )
                }
                .getOrNull() ?: return true
        val path = info.sourceDir ?: return true
        cache[packageName]?.let { (cached, trusted) -> if (cached == path) return trusted }
        val trusted = runCatching { read(context, info) }.getOrNull() ?: true
        cache[packageName] = path to trusted
        return trusted
    }

    private fun read(context: Context, info: ApplicationInfo): Boolean? {
        val legacy = info.targetSdkVersion < Build.VERSION_CODES.N
        val app = context.createPackageContext(info.packageName, Context.CONTEXT_IGNORE_SECURITY)
        val config =
            app.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
                manifestConfig(manifest, info.packageName) ?: return null
            }
        if (config == 0) return legacy
        val debuggable = info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return app.resources.getXml(config).use { trustsUser(it, debuggable) } ?: legacy
    }

    /** The `networkSecurityConfig` resource id (0 when absent), or null if this is another manifest. */
    private fun manifestConfig(parser: XmlResourceParser, packageName: String): Int? {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "manifest" -> if (parser.getAttributeValue(null, "package") != packageName) return null
                "application" -> return parser.getAttributeResourceValue(Android, "networkSecurityConfig", 0)
            }
        }
        return 0
    }

    /**
     * Base-config trust anchors decide for every domain we would intercept. Null means the config
     * does not set them, so the platform default for the app's target SDK applies.
     */
    private fun trustsUser(parser: XmlResourceParser, debuggable: Boolean): Boolean? {
        var section: String? = null
        var anchors = false
        var base: Boolean? = null
        var debugUser = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG ->
                    when (parser.name) {
                        "base-config", "domain-config", "debug-overrides" ->
                            if (parser.depth == 2) section = parser.name
                        "trust-anchors" -> {
                            anchors = true
                            if (section == "base-config" && base == null) base = false
                        }
                        "certificates" ->
                            if (anchors && parser.getAttributeValue(null, "src") == "user") {
                                when (section) {
                                    "base-config" -> base = true
                                    "debug-overrides" -> debugUser = true
                                }
                            }
                    }
                XmlPullParser.END_TAG ->
                    when (parser.name) {
                        "trust-anchors" -> anchors = false
                        "base-config", "domain-config", "debug-overrides" ->
                            if (parser.depth == 2) section = null
                    }
            }
        }
        return if (debuggable && debugUser) true else base
    }
}

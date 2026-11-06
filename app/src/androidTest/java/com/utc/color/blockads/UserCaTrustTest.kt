package com.utc.color.blockads

import android.content.pm.PackageManager
import com.utc.color.blockads.core.data.UserCaTrust
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads installed apps' network security configs only; no UI, VPN or CA changes. */
class UserCaTrustTest {
    private fun installed(packageName: String) =
        runCatching {
                targetContext.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            }
            .isSuccess

    private fun check(packageName: String, expected: Boolean) {
        assumeTrue("$packageName not installed", installed(packageName))
        assertEquals(packageName, expected, UserCaTrust.trusts(targetContext, packageName))
    }

    // base-config trusts system only; user CAs only in debug-overrides of a release build.
    @Test fun googleGoRejectsUserCa() = check("com.google.android.apps.searchlite", false)

    // base-config lists <certificates src="user"/>.
    @Test fun chromeTrustsUserCa() = check("com.android.chrome", true)

    @Test fun samsungInternetTrustsUserCa() = check("com.sec.android.app.sbrowser", true)

    // base-config without trust-anchors on a modern target SDK: system CAs only.
    @Test fun heytapBrowserRejectsUserCa() = check("com.heytap.browser", false)
}

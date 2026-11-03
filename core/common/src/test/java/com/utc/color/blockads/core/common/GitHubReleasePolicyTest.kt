package com.utc.color.blockads.core.common

import org.junit.Assert.*
import org.junit.Test

class GitHubReleasePolicyTest {
    @Test
    fun onlyAcceptsHttpsAssetsInsideThisRepositoryRelease() {
        val base = "https://github.com/ToThangGTVT/coloros-block-ads/releases/download/v0.6.0/"
        GitHubReleasePolicy.validateDownloadUrl(base + "BlockAds-0.6.0.apk")
        GitHubReleasePolicy.validateDownloadUrl(base + "update.json")
        for (url in
            listOf(
                base.replace("https:", "http:") + "app.apk",
                base.replace("github.com", "github.com.evil.test") + "app.apk",
                base.replace("github.com", "user@github.com") + "app.apk",
                base.replace("github.com", "github.com:8443") + "app.apk",
                base.replace("ToThangGTVT", "another-owner") + "app.apk",
                base + "../app.apk",
                base + "%2e%2e",
                base + "nested%2fapp.apk",
                base + "app.apk?redirect=evil",
                base + "app.apk#fragment",
            )) assertTrue(
            url,
            runCatching { GitHubReleasePolicy.validateDownloadUrl(url) }.isFailure,
        )
    }
}

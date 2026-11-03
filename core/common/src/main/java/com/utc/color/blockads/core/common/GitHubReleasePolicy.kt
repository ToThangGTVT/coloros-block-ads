package com.utc.color.blockads.core.common

import java.net.URI

object GitHubReleasePolicy {
    const val Repository = "ToThangGTVT/coloros-block-ads"
    const val ReleasesUrl = "https://github.com/$Repository/releases/latest"

    fun validateDownloadUrl(value: String) {
        val uri = URI(value)
        val segments = uri.path.orEmpty().split('/')
        require(
            uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.query == null &&
                uri.fragment == null &&
                uri.rawPath.startsWith("/$Repository/releases/download/") &&
                segments.size == 7 &&
                segments.drop(1).all { it.isNotBlank() && it != "." && it != ".." }
        ) {
            "Đường dẫn cập nhật không hợp lệ"
        }
    }
}

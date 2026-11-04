package com.utc.color.blockads.core.common

data class HttpsSettings(
    val enabled: Boolean = false,
    val apps: List<String> =
        listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
        ),
)

data class CertificateState(
    val created: Boolean = false,
    val installed: Boolean = false,
    val fingerprint: String? = null,
    val hardwareBacked: Boolean = false,
    val error: String? = null,
)

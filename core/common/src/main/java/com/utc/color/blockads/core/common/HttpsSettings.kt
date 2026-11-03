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
    val domains: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
)

fun normalizeHttpsDomain(input: String): String {
    val value = java.net.IDN.toASCII(input.trim().trimEnd('.')).lowercase(java.util.Locale.ROOT)
    require(
        value.length <= 253 &&
            value.contains('.') &&
            value.split('.').all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    !label.startsWith('-') &&
                    !label.endsWith('-') &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            } &&
            !value.all { it.isDigit() || it == '.' }
    ) {
        "Cần tên domain, không dùng URL, IP hoặc ký tự đại diện"
    }
    return value
}

data class CertificateState(
    val created: Boolean = false,
    val installed: Boolean = false,
    val fingerprint: String? = null,
    val hardwareBacked: Boolean = false,
    val error: String? = null,
)

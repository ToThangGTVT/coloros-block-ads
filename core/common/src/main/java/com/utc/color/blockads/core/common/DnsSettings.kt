package com.utc.color.blockads.core.common

enum class DnsUpstream(
    val title: String,
    val protocol: String,
    val ip: String = "",
    val serverName: String = "",
) {
    System("DNS của mạng", "system"),
    Cloudflare("Cloudflare · UDP", "udp", "1.1.1.1"),
    CloudflareDoH("Cloudflare · DoH", "https", "1.1.1.1", "cloudflare-dns.com"),
    CloudflareDoT("Cloudflare · DoT", "tls", "1.1.1.1", "one.one.one.one"),
    Quad9("Quad9 · UDP", "udp", "9.9.9.9"),
    AdGuardDoH("AdGuard · DoH", "https", "94.140.14.14", "dns.adguard-dns.com"),
    AdGuardDoQ("AdGuard · DoQ", "quic", "94.140.14.14", "dns.adguard-dns.com"),
    Custom("Tùy chỉnh DoH / DoT", "custom"),
}

enum class BlockResponse {
    Zero,
    NxDomain,
}

data class DnsSettings(
    val upstream: DnsUpstream = DnsUpstream.System,
    val blockResponse: BlockResponse = BlockResponse.Zero,
    val logging: Boolean = true,
    val customUrl: String = "https://cloudflare-dns.com/dns-query",
    val customBootstrap: String = "1.1.1.1",
)

/**
 * A rule list. DNS lists only block domains; [browser] lists (EasyList, AdGuard extension filters)
 * only apply to decrypted HTTPS of browsers, so they never block a DNS answer.
 */
data class FilterList(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = false,
    val updatedAt: Long = 0,
    val ruleCount: Int = 0,
    val error: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val browser: Boolean = false,
)

data class DnsEndpoint(
    val protocol: String,
    val host: String,
    val port: Int,
    val path: String,
    val bootstrap: String,
)

fun parseDnsEndpoint(url: String, bootstrap: String): DnsEndpoint {
    require(url.length <= 2048 && bootstrap.length <= 64) { "Địa chỉ DNS quá dài" }
    val uri = java.net.URI(url.trim())
    require(
        uri.scheme in listOf("https", "tls") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    ) {
        "Cần URL https:// (DoH) hoặc tls:// (DoT) hợp lệ"
    }
    val ipv4 =
        bootstrap.split('.').let {
            it.size == 4 &&
                it.all { label ->
                    label.isNotEmpty() &&
                        (label == "0" || !label.startsWith("0")) &&
                        label.all(Char::isDigit) &&
                        label.toIntOrNull() in 0..255
                }
        }
    val ipv6 =
        bootstrap.contains(':') &&
            '%' !in bootstrap &&
            runCatching {
                    java.net.URI("tls://[$bootstrap]").host != null &&
                        java.net.InetAddress.getByName(bootstrap) is java.net.Inet6Address
                }
                .getOrDefault(false)
    require(ipv4 || ipv6) { "Cần IP bootstrap cố định để tránh DNS loop" }
    val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 853 else uri.port
    require(port in 1..65535) { "Port không hợp lệ" }
    require(uri.scheme != "tls" || (uri.path.orEmpty().isEmpty() && uri.query == null)) {
        "DoT không dùng path/query"
    }
    return DnsEndpoint(
        if (uri.scheme == "https") "https" else "tls",
        uri.host,
        port,
        (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/dns-query") +
            (uri.rawQuery?.let { "?$it" } ?: ""),
        if (ipv6) java.net.InetAddress.getByName(bootstrap).hostAddress!! else bootstrap,
    )
}

data class FilterStatus(
    val loading: Boolean = true,
    val loaded: Int = 0,
    val ignored: Int = 0,
    val error: String? = null,
)

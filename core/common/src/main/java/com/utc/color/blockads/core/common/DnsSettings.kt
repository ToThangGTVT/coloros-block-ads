package com.utc.color.blockads.core.common

import com.utc.color.blockads.core.common.R as L10n
enum class DnsUpstream(
    val titleRes: Int,
    val protocol: String,
    val ip: String = "",
    val serverName: String = "",
) {
    System(L10n.string.dns_network, "system"),
    Cloudflare(L10n.string.dns_cloudflare_udp, "udp", "1.1.1.1"),
    CloudflareDoH(L10n.string.dns_cloudflare_doh, "https", "1.1.1.1", "cloudflare-dns.com"),
    CloudflareDoT(L10n.string.dns_cloudflare_dot, "tls", "1.1.1.1", "one.one.one.one"),
    Quad9(L10n.string.dns_quad9_udp, "udp", "9.9.9.9"),
    AdGuardDoH(L10n.string.dns_adguard_doh, "https", "94.140.14.14", "dns.adguard-dns.com"),
    AdGuardDoQ(L10n.string.dns_adguard_doq, "quic", "94.140.14.14", "dns.adguard-dns.com"),
    Custom(L10n.string.dns_custom, "custom"),
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
    require(url.length <= 2048 && bootstrap.length <= 64) { localizedError(L10n.string.dns_address_too_long) }
    val uri = java.net.URI(url.trim())
    require(
        uri.scheme in listOf("https", "tls") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    ) {
        localizedError(L10n.string.dns_url_invalid)
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
    require(ipv4 || ipv6) { localizedError(L10n.string.dns_bootstrap_invalid) }
    val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 853 else uri.port
    require(port in 1..65535) { localizedError(L10n.string.port_invalid) }
    require(uri.scheme != "tls" || (uri.path.orEmpty().isEmpty() && uri.query == null)) {
        localizedError(L10n.string.dot_path_invalid)
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

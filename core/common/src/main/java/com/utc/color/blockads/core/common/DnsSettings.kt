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
}

enum class BlockResponse {
    Zero,
    NxDomain,
}

data class DnsSettings(
    val upstream: DnsUpstream = DnsUpstream.System,
    val blockResponse: BlockResponse = BlockResponse.Zero,
    val logging: Boolean = true,
)

data class FilterList(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = false,
    val updatedAt: Long = 0,
    val ruleCount: Int = 0,
    val error: String? = null,
)

data class FilterStatus(
    val loading: Boolean = true,
    val loaded: Int = 0,
    val ignored: Int = 0,
    val error: String? = null,
)

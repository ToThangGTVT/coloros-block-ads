package com.utc.color.blockads.core.common

import org.junit.Assert.*
import org.junit.Test

class DnsEndpointTest {
    @Test
    fun encryptedEndpointsPreservePathQueryAndCanonicalizeIpv6WithoutBootstrapDns() {
        val doh = parseDnsEndpoint("https://dns.test:8443/query?token=example", "1.1.1.1")
        assertEquals(8443, doh.port)
        assertEquals("/query?token=example", doh.path)
        assertEquals(
            "/dns-query?token=example",
            parseDnsEndpoint("https://dns.test?token=example", "1.1.1.1").path,
        )
        val dot = parseDnsEndpoint("tls://dns.test", "2001:db8::1")
        assertEquals(853, dot.port)
        assertTrue(dot.bootstrap.contains(":"))
    }

    @Test
    fun insecureUrlsCredentialsAndInvalidBootstrapAreRejected() {
        for ((url, ip) in
            listOf(
                "http://dns.test/query" to "1.1.1.1",
                "https://user:pass@dns.test/query" to "1.1.1.1",
                "tls://dns.test/query" to "1.1.1.1",
                "https://dns.test:99999/query" to "1.1.1.1",
                "https://dns.test/query" to "dns.test",
                "https://dns.test/query" to "999.1.1.1",
                "https://dns.test/query" to "01.1.1.1",
                "https://dns.test/query" to "bad::host",
            )) {
            assertThrows(IllegalArgumentException::class.java) { parseDnsEndpoint(url, ip) }
        }
    }
}

package com.utc.color.blockads.core.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

internal data class UnderlyingNetwork(
    val network: Network?,
    val dns: String,
    val privateDns: Boolean = false,
)

internal class UnderlyingNetworkMonitor(context: Context, private val onChange: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()

            override fun onLost(network: Network) = onChange()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = onChange()

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
                onChange()
        }

    fun start() {
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            callback,
        )
    }

    fun stop() = connectivity.unregisterNetworkCallback(callback)

    fun current(): UnderlyingNetwork {
        val selected =
            connectivity.allNetworks
                .mapNotNull { network ->
                    val caps =
                        connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    )
                        return@mapNotNull null
                    network to
                        ((if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10
                        else 0) +
                            (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 0) +
                            (if (network == connectivity.activeNetwork) 1 else 0))
                }
                .maxByOrNull { it.second }
                ?.first
        val servers = selected?.let { connectivity.getLinkProperties(it)?.dnsServers }.orEmpty()
        val dns =
            (servers.firstOrNull { it is Inet4Address }
                    ?: servers.firstOrNull { !it.isLinkLocalAddress })
                ?.hostAddress
                .orEmpty()
        return UnderlyingNetwork(
            selected,
            dns,
            selected?.let { connectivity.getLinkProperties(it)?.isPrivateDnsActive } == true,
        )
    }
}

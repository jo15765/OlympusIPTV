package com.olympus.iptv.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.olympus.iptv.vpn.AppTunnel
import com.olympus.iptv.vpn.TunnelState

/**
 * Tells the player when Wi-Fi returns or a VPN city finishes connecting.
 * The player decides whether a stream is actually playing.
 */
internal class NetworkWatcher(
    private val context: Context,
    private val onReconnect: (Boolean) -> Unit
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var tunnel: ((TunnelState) -> Unit)? = null
    private var lastTransport: Int = -1

    fun start() {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onReconnect(false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val transport = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                    else -> 0
                }
                if (lastTransport != -1 && lastTransport != transport) {
                    lastTransport = transport
                    com.olympus.iptv.playlist.HttpClients.evictConnections()
                    onReconnect(true)
                } else {
                    lastTransport = transport
                }
            }

            override fun onLost(network: Network) {
                com.olympus.iptv.playlist.HttpClients.evictConnections()
                onReconnect(true)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, next) }
        callback = next
        val listener: (TunnelState) -> Unit = { state ->
            if (state is TunnelState.On) onReconnect(true)
        }
        tunnel = listener
        AppTunnel.addListener(listener)
    }

    fun stop() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        callback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        callback = null
        tunnel?.let { AppTunnel.removeListener(it) }
        tunnel = null
    }
}

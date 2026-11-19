package com.olympus.iptv.vpn

import android.content.Context
import android.net.VpnService

internal object VpnDesk {
    @Volatile var account: VpnAccount? = null
    @Volatile var provider: String = ""

    fun status(context: Context): Map<String, Any?> {
        val saved = VpnStore.load(context)
        val state = AppTunnel.state
        val label = when (state) {
            is TunnelState.On -> state.label
            is TunnelState.Reconnecting -> state.label
            is TunnelState.Connecting -> state.label
            else -> saved?.label.orEmpty()
        }
        val cities = preferCity(account?.cities.orEmpty(), saved?.cityId).map { mapOf("id" to it.id, "name" to it.name) }
        return mapOf(
            "provider" to (provider.ifBlank { saved?.provider.orEmpty() }),
            "username" to saved?.username.orEmpty(),
            "label" to label,
            "cityId" to saved?.cityId.orEmpty(),
            "backupCityId" to saved?.backupCityId.orEmpty(),
            "backupLabel" to saved?.backupLabel.orEmpty(),
            "state" to when (state) {
                is TunnelState.Off -> "off"
                is TunnelState.Connecting -> "connecting"
                is TunnelState.On -> "on"
                is TunnelState.Reconnecting -> "reconnecting"
                is TunnelState.Failed -> "failed"
            },
            "message" to if (state is TunnelState.Failed) state.message else "",
            "needsConsent" to AppTunnel.needsConsent,
            "cities" to cities
        )
    }

    fun signIn(context: Context, chosen: String, username: String, password: String, code: String, profile: String) {
        val signed = when (chosen) {
            "pia" -> PiaClient.signIn(context, username, password)
            "proton" -> ProtonClient.signIn(username, password, code)
            "surfshark" -> SurfsharkClient.signIn(username, password)
            "nord" -> NordClient.signIn(username, password)
            "wireguard" -> {
                val config = parseWireGuard(profile)
                VpnAccount(listOf(VpnCity("profile", config.label))) { config }
            }
            else -> throw VpnAuthException("Choose a VPN.")
        }
        account = signed
        provider = chosen
        val saved = VpnStore.load(context) ?: SavedVpn()
        saved.provider = chosen
        if (username.isNotBlank()) saved.username = username
        if (password.isNotBlank()) saved.password = password
        VpnStore.save(context, saved)
    }

    fun connect(context: Context, cityId: String, backupCityId: String = ""): Boolean {
        val signed = account
        val config = if (cityId.isNotBlank() && signed != null) {
            signed.config(cityId)
        } else {
            VpnStore.load(context)?.toConfig()
        } ?: throw VpnAuthException("Choose a city, then connect.")
        val backup = if (backupCityId.isNotBlank() && backupCityId != cityId && signed != null) {
            signed.config(backupCityId)
        } else {
            null
        }
        val saved = VpnStore.load(context) ?: SavedVpn()
        saved.provider = config.provider.ifBlank { provider.ifBlank { saved.provider } }
        saved.label = config.label
        saved.cityId = cityId.ifBlank { saved.cityId }
        if (backup != null) saved.storeBackup(backupCityId, backup) else if (backupCityId.isBlank()) saved.clearBackup()
        saved.privateKey = config.privateKey
        saved.address = config.address
        saved.dns = config.dns
        saved.peerKey = config.peerKey
        saved.presharedKey = config.presharedKey
        saved.endpointHost = config.endpointHost
        saved.endpointPort = config.endpointPort
        saved.connected = true
        VpnStore.save(context, saved)
        if (VpnService.prepare(context) != null) {
            AppTunnel.needsConsent = true
            AppTunnel.publish(TunnelState.Connecting(config.label))
            return true
        }
        AppTunnel.needsConsent = false
        AppTunnel.connect(context)
        return false
    }

    fun disconnect(context: Context) {
        AppTunnel.needsConsent = false
        AppTunnel.disconnect(context)
    }
}

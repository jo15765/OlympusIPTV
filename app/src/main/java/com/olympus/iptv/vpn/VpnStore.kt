package com.olympus.iptv.vpn

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SavedVpn {
    var provider: String? = null
    var label: String? = null
    var username: String? = null
    var password: String? = null
    var privateKey: String? = null
    var address: String? = null
    var dns: List<String>? = null
    var peerKey: String? = null
    var presharedKey: String? = null
    var endpointHost: String? = null
    var endpointPort: Int? = null
    var cityId: String? = null
    var backupCityId: String? = null
    var backupLabel: String? = null
    var backupPrivateKey: String? = null
    var backupAddress: String? = null
    var backupDns: List<String>? = null
    var backupPeerKey: String? = null
    var backupPresharedKey: String? = null
    var backupEndpointHost: String? = null
    var backupEndpointPort: Int? = null
    var connected: Boolean? = null

    internal fun toConfig(): TunnelConfig? {
        val key = privateKey ?: return null
        val peer = peerKey ?: return null
        val host = endpointHost ?: return null
        val port = endpointPort ?: return null
        val ip = address ?: return null
        if (runCatching { WgKeys.raw(key); WgKeys.raw(peer) }.isFailure) return null
        return TunnelConfig(
            provider.orEmpty(),
            label.orEmpty().ifBlank { host },
            key,
            ip,
            dns.orEmpty().ifEmpty { listOf("1.1.1.1") },
            peer,
            presharedKey,
            host,
            port
        )
    }

    internal fun toBackupConfig(): TunnelConfig? {
        val key = backupPrivateKey ?: return null
        val peer = backupPeerKey ?: return null
        val host = backupEndpointHost ?: return null
        val port = backupEndpointPort ?: return null
        val ip = backupAddress ?: return null
        if (runCatching { WgKeys.raw(key); WgKeys.raw(peer) }.isFailure) return null
        return TunnelConfig(
            provider.orEmpty(),
            backupLabel.orEmpty().ifBlank { host },
            key,
            ip,
            backupDns.orEmpty().ifEmpty { listOf("1.1.1.1") },
            peer,
            backupPresharedKey,
            host,
            port
        )
    }

    internal fun storeBackup(cityId: String, config: TunnelConfig) {
        backupCityId = cityId
        backupLabel = config.label
        backupPrivateKey = config.privateKey
        backupAddress = config.address
        backupDns = config.dns
        backupPeerKey = config.peerKey
        backupPresharedKey = config.presharedKey
        backupEndpointHost = config.endpointHost
        backupEndpointPort = config.endpointPort
    }

    internal fun clearBackup() {
        backupCityId = null
        backupLabel = null
        backupPrivateKey = null
        backupAddress = null
        backupDns = null
        backupPeerKey = null
        backupPresharedKey = null
        backupEndpointHost = null
        backupEndpointPort = null
    }
}

internal object VpnStore {
    private const val FILE = "vpn-session.bin"
    private const val ALIAS = "olympus-iptv-vpn"
    private val gson get() = com.olympus.iptv.data.AppJson.gson

    fun load(context: Context): SavedVpn? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching {
            val plain = open(file.readBytes())
            gson.fromJson(plain.toString(Charsets.UTF_8), SavedVpn::class.java)
        }.getOrNull()
    }

    fun save(context: Context, saved: SavedVpn) {
        File(context.filesDir, FILE).writeBytes(seal(gson.toJson(saved).toByteArray(Charsets.UTF_8)))
    }

    fun setConnected(context: Context, connected: Boolean) {
        val saved = load(context) ?: return
        saved.connected = connected
        save(context, saved)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return iv + body
    }

    private fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

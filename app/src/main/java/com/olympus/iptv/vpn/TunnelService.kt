package com.olympus.iptv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.olympus.iptv.R
import com.olympus.iptv.ui.browse.BrowseActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class TunnelService : VpnService() {
    private var engine: TunnelEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AppTunnel.STOP) {
            VpnStore.setConnected(this, false)
            AppTunnel.publish(TunnelState.Off)
            shutdown()
            return START_NOT_STICKY
        }
        val saved = VpnStore.load(this)
        val config = saved?.toConfig()
        if (config == null) {
            VpnStore.setConnected(this, false)
            shutdown()
            return START_NOT_STICKY
        }
        startInForeground(getString(R.string.vpn_connecting))
        engine?.stop()
        engine = null
        AppTunnel.attach(this)
        val next = TunnelEngine(this, config, saved.toBackupConfig(), { text -> show(text) }) { message ->
            VpnStore.setConnected(this, false)
            AppTunnel.publish(TunnelState.Failed(message))
            shutdown()
        }
        engine = next
        AppTunnel.publish(TunnelState.Connecting(config.label))
        next.start()
        return START_STICKY
    }

    override fun onRevoke() {
        VpnStore.setConnected(this, false)
        AppTunnel.publish(TunnelState.Off)
        shutdown()
    }

    override fun onDestroy() {
        AppTunnel.detach(this)
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    fun note(text: String) = show(text)

    private fun shutdown() {
        engine?.stop()
        engine = null
        AppTunnel.detach(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTICE, notification)
        }
    }

    private fun show(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTICE, notification(text))
    }

    private var channelCreated = false

    private fun notification(text: String): Notification {
        if (!channelCreated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                if (manager.getNotificationChannel(CHANNEL) == null) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.vpn_card), NotificationManager.IMPORTANCE_LOW))
                }
                channelCreated = true
            }
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BrowseActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).setAction(AppTunnel.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.vpn_card))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.vpn_disconnect), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "olympus-iptv-vpn"
        private const val NOTICE = 47
    }
}

internal class TunnelEngine(
    private val service: TunnelService,
    private val config: TunnelConfig,
    private val backup: TunnelConfig?,
    private val onText: (String) -> Unit,
    private val onFailed: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var active: WgHandshake? = null
    private var pending: WgHandshake? = null
    private var previous: WgHandshake? = null
    private val sendLock = Any()
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunIn: FileInputStream? = null
    @Volatile private var tunOut: FileOutputStream? = null
    private var openAddress = ""
    private var openDns = ""
    private var preferBackup = false
    private var udp: DatagramSocket? = null
    private var reader: Thread? = null
    private var writer: Thread? = null

    fun start() {
        running = true
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            try {
                run()
            } catch (error: Exception) {
                if (running) onFailed(error.message ?: "The VPN stopped.")
            }
        }, "vpn-udp").start()
    }

    fun stop() {
        running = false
        active = null
        pending = null
        previous = null
        runCatching { udp?.close() }
        runCatching { tun?.close() }
    }

    private fun run() {
        val builder = service.Builder()
        val address = config.address.substringBefore(",").trim().split("/")
        builder.addAddress(address[0], address.getOrNull(1)?.toIntOrNull() ?: 32)
        builder.setMtu(1280)
        builder.setSession(config.label.take(50).ifBlank { "Olympus IPTV" })
        builder.setBlocking(true)
        builder.addAllowedApplication(service.packageName)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        check(LanRoutes.excludesRfc1918()) { "VPN routes must leave RFC1918 LAN addresses on the local network." }
        for ((host, prefix) in LanRoutes.publicV4()) builder.addRoute(host, prefix)
        for (dns in config.dns) {
            val literal = dns.substringBefore("%").trim()
            if (LanRoutes.parse(literal) == null) continue

            if (LanRoutes.isPrivateV4(literal)) continue
            runCatching { builder.addDnsServer(literal) }
        }
        runCatching {
            builder.addAddress("fd00:ffff:ffff::2", 128)
            builder.addRoute("2000::", 3)
        }
        val parcel = builder.establish() ?: throw IllegalStateException("The VPN permission was not granted.")
        tun = parcel
        openAddress = config.address
        openDns = config.dns.joinToString()
        tunIn = FileInputStream(parcel.fileDescriptor)
        tunOut = FileOutputStream(parcel.fileDescriptor)
        writer = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            val buffer = ByteArray(2048)
            val sealed = ByteArray(2048)
            val v6 = ByteArray(60)
            while (running) {
                val size = try {
                    tunIn?.read(buffer) ?: break
                } catch (_: Exception) {
                    if (!running) break
                    continue
                }
                if (size < 0) {
                    if (!running) break
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                if (size == 0) continue
                val version = buffer[0].toInt() ushr 4
                if (version == 6) {
                    val reset = ipv6Reset(buffer, size, v6)
                    if (reset > 0) runCatching { tunOut?.write(v6, 0, reset) }
                    continue
                }
                if (version != 4 || sealed.size < size + 32) continue
                val written = synchronized(sendLock) {
                    val session = active ?: return@synchronized -1
                    session.sealInto(buffer, size, sealed)
                }
                if (written > 0) runCatching { send(sealed, written) }
            }
        }, "vpn-tun")
        writer?.start()
        val inbound = ByteArray(2048)
        val incoming = DatagramPacket(inbound, inbound.size)
        val plain = ByteArray(2048)
        val keep = ByteArray(64)
        var live = false
        var privateKey = WgKeys.raw(config.privateKey)
        var peerKey = WgKeys.raw(config.peerKey)
        var preshared = config.presharedKey?.let { WgKeys.raw(it) } ?: ByteArray(32)
        while (running) {
            val wireConfig = if (preferBackup && backup != null) backup else config
            if (wireConfig.address != openAddress || wireConfig.dns.joinToString() != openDns) reopen(wireConfig)
            privateKey = WgKeys.raw(wireConfig.privateKey)
            peerKey = WgKeys.raw(wireConfig.peerKey)
            preshared = wireConfig.presharedKey?.let { WgKeys.raw(it) } ?: ByteArray(32)
            var failover = false
            val socket = DatagramSocket()
            try {
                socket.soTimeout = 1000
                if (!service.protect(socket)) throw IllegalStateException("The VPN socket could not stay on the home network.")
                runCatching {
                    socket.receiveBufferSize = 512 * 1024
                    socket.sendBufferSize = 512 * 1024
                }
                socket.connect(InetSocketAddress(InetAddress.getByName(wireConfig.endpointHost), wireConfig.endpointPort))
                udp = socket
                if (live) {
                    synchronized(sendLock) { active = null }
                    previous = null
                    AppTunnel.publish(TunnelState.Reconnecting(wireConfig.label))
                }
                val handshake = WgHandshake(privateKey, peerKey, preshared)
                pending = handshake
                send(handshake.initiation())
                val attempt = System.currentTimeMillis()
                var sentAt = attempt
                var rekeyAt = attempt
                var lost = false
                while (running && !lost) {
                    val now = System.currentTimeMillis()
                    if (active == null && now - attempt > 20_000L) {
                        if (!live && backup != null && wireConfig !== backup) {
                            preferBackup = true
                            failover = true
                            lost = true
                        } else if (live) {
                            lost = true
                        } else {
                            throw IllegalStateException("The VPN server did not answer.")
                        }
                        continue
                    }
                    if (pending != null && active != null && now - rekeyAt > 20_000L) {
                        pending = null
                        rekeyAt = now - 110_000L
                    }
                    val waiting = pending
                    if (waiting != null && now - sentAt > 5_000L) {
                        send(waiting.outbound())
                        sentAt = now
                    }
                    if (active != null && pending == null && now - rekeyAt > 120_000L) {
                        val next = WgHandshake(privateKey, peerKey, preshared)
                        pending = next
                        send(next.initiation())
                        rekeyAt = now
                        sentAt = now
                    }
                    incoming.setLength(inbound.size)
                    try {
                        socket.receive(incoming)
                    } catch (_: java.net.SocketTimeoutException) {
                        if (active != null && System.currentTimeMillis() - sentAt > 25_000L) {
                            val kept = synchronized(sendLock) {
                                val session = active ?: return@synchronized -1
                                session.sealInto(keep, 0, keep)
                            }
                            if (kept > 0) send(keep, kept)
                            sentAt = System.currentTimeMillis()
                        }
                        continue
                    } catch (_: Exception) {
                        lost = true
                        continue
                    }
                    val length = incoming.length
                    if (length <= 0) continue
                    val type = inbound[0].toInt() and 0xff
                    when (type) {
                        2 -> {
                            val next = pending
                            if (next != null && next.consumeResponse(inbound.copyOf(length))) {
                                val became = active == null
                                previous = active
                                synchronized(sendLock) { active = next }
                                pending = null
                                live = true
                                rekeyAt = System.currentTimeMillis()
                                sentAt = rekeyAt
                                val kept = synchronized(sendLock) { next.sealInto(keep, 0, keep) }
                                if (kept > 0) send(keep, kept)
                                if (became) {
                                    onText(service.getString(R.string.vpn_connected, wireConfig.label))
                                    AppTunnel.publish(TunnelState.On(wireConfig.label))
                                }
                            }
                        }
                        3 -> {
                            val next = pending
                            if (next != null && next.consumeCookie(inbound.copyOf(length), System.currentTimeMillis())) {
                                send(next.outbound())
                            }
                        }
                        4 -> {
                            val session = active
                            if (session != null) {
                                var opened = session.openInto(inbound, length, plain)
                                if (opened < 0) {
                                    val prior = previous
                                    if (prior != null) opened = prior.openInto(inbound, length, plain)
                                }
                                if (opened > 0) runCatching { tunOut?.write(plain, 0, opened) }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (!live && running && backup != null && wireConfig !== backup) {
                    preferBackup = true
                    failover = true
                } else if (!live || !running) {
                    throw error
                }
            } finally {
                if (udp === socket) udp = null
                pending = null
                runCatching { socket.close() }
            }
            if (!running) break
            if (!live && !failover) throw IllegalStateException("The VPN server did not answer.")
            if (live && backup != null) preferBackup = wireConfig === config
            synchronized(sendLock) { active = null }
            previous = null
            val nextLabel = if (preferBackup && backup != null) backup.label else config.label
            AppTunnel.publish(TunnelState.Reconnecting(nextLabel))
            try {
                Thread.sleep(if (failover && !live) 300 else 1500)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun reopen(next: TunnelConfig) {
        val builder = service.Builder()
        val address = next.address.substringBefore(",").trim().split("/")
        builder.addAddress(address[0], address.getOrNull(1)?.toIntOrNull() ?: 32)
        builder.setMtu(1280)
        builder.setSession(next.label.take(50).ifBlank { "Olympus IPTV" })
        builder.setBlocking(true)
        builder.addAllowedApplication(service.packageName)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        check(LanRoutes.excludesRfc1918()) { "VPN routes must leave RFC1918 LAN addresses on the local network." }
        for ((host, prefix) in LanRoutes.publicV4()) builder.addRoute(host, prefix)
        for (dns in next.dns) {
            val literal = dns.substringBefore("%").trim()
            if (LanRoutes.parse(literal) == null) continue
            if (LanRoutes.isPrivateV4(literal)) continue
            runCatching { builder.addDnsServer(literal) }
        }
        runCatching {
            builder.addAddress("fd00:ffff:ffff::2", 128)
            builder.addRoute("2000::", 3)
        }
        val parcel = builder.establish() ?: return
        val old = tun
        tun = parcel
        tunIn = FileInputStream(parcel.fileDescriptor)
        tunOut = FileOutputStream(parcel.fileDescriptor)
        openAddress = next.address
        openDns = next.dns.joinToString()
        runCatching { old?.close() }
    }

    private val wire = DatagramPacket(ByteArray(0), 0)

    private fun send(bytes: ByteArray, length: Int = bytes.size) {
        val socket = udp ?: return
        if (length <= 0) return
        synchronized(socket) {
            wire.setData(bytes, 0, length)
            socket.send(wire)
        }
    }
}

private fun ipv6Reset(packet: ByteArray, size: Int, out: ByteArray): Int {
    if (size < 60 || out.size < 60 || packet[6].toInt() != 6) return -1
    val flags = packet[53].toInt() and 0xff
    if (flags and 0x02 == 0 || flags and 0x04 != 0) return -1
    val header = ((packet[52].toInt() and 0xff) ushr 4) * 4
    if (header < 20 || 40 + header > size) return -1
    out[0] = 0x60.toByte()
    out[1] = 0
    out[2] = 0
    out[3] = 0
    out[4] = 0
    out[5] = 20
    out[6] = 6
    out[7] = 64
    packet.copyInto(out, 8, 24, 40)
    packet.copyInto(out, 24, 8, 24)
    out[40] = packet[42]
    out[41] = packet[43]
    out[42] = packet[40]
    out[43] = packet[41]
    val sequence = readBe(packet, 44)
    val acknowledge = sequence + (size - 40 - header) + 1
    writeBe(out, 44, 0)
    writeBe(out, 48, acknowledge)
    out[52] = (5 shl 4).toByte()
    out[53] = 0x14
    out[54] = 0
    out[55] = 0
    out[58] = 0
    out[59] = 0
    val sum = checksum(out)
    out[56] = (sum ushr 8).toByte()
    out[57] = sum.toByte()
    return 60
}

private fun checksum(packet: ByteArray): Int {
    var total = 0
    var offset = 8
    repeat(2) {
        var index = offset
        val end = offset + 16
        while (index < end) {
            total += ((packet[index].toInt() and 0xff) shl 8) or (packet[index + 1].toInt() and 0xff)
            index += 2
        }
        offset = 24
    }
    total += 20
    total += 6
    var index = 40
    while (index < 60) {
        total += ((packet[index].toInt() and 0xff) shl 8) or (packet[index + 1].toInt() and 0xff)
        index += 2
    }
    while (total shr 16 != 0) total = (total and 0xffff) + (total shr 16)
    return total.inv() and 0xffff
}

private fun readBe(packet: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 0 until 4) value = (value shl 8) or (packet[offset + index].toLong() and 0xff)
    return value
}

private fun writeBe(packet: ByteArray, offset: Int, value: Long) {
    packet[offset] = (value ushr 24).toByte()
    packet[offset + 1] = (value ushr 16).toByte()
    packet[offset + 2] = (value ushr 8).toByte()
    packet[offset + 3] = value.toByte()
}

sealed class TunnelState {
    data object Off : TunnelState()
    data class Connecting(val label: String) : TunnelState()
    data class On(val label: String) : TunnelState()
    data class Reconnecting(val label: String) : TunnelState()
    data class Failed(val message: String) : TunnelState()
}

object AppTunnel {
    const val STOP = "com.olympus.iptv.vpn.STOP"
    @Volatile var state: TunnelState = TunnelState.Off
    @Volatile var needsConsent = false
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(TunnelState) -> Unit>()
    @Volatile private var service: TunnelService? = null

    fun addListener(listener: (TunnelState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (TunnelState) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(next: TunnelState) {
        state = next
        for (listener in listeners) listener(next)
    }

    fun attach(value: TunnelService) {
        service = value
    }

    fun detach(value: TunnelService) {
        if (service === value) service = null
    }

    fun protect(socket: java.net.Socket): Boolean = service?.protect(socket) == true

    fun connect(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java))
    }

    fun disconnect(context: Context) {
        context.startService(Intent(context, TunnelService::class.java).setAction(STOP))
        publish(TunnelState.Off)
    }

    fun restore(context: Context) {
        val saved = VpnStore.load(context) ?: return
        if (saved.connected != true || saved.toConfig() == null) return
        if (VpnService.prepare(context) != null) return
        connect(context)
    }
}

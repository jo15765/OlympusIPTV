package com.olympus.iptv.ui.browse

import android.view.View
import android.view.ViewStub
import com.olympus.iptv.databinding.ActivityBrowseBinding
import com.olympus.iptv.databinding.BrowsePaneAboutBinding
import com.olympus.iptv.databinding.BrowsePaneAdminBinding
import com.olympus.iptv.databinding.BrowsePaneDeviceBinding
import com.olympus.iptv.databinding.BrowsePaneRefreshBinding
import com.olympus.iptv.databinding.BrowsePaneVpnBinding

/**
 * Heavy setting panes live behind ViewStubs so they are not inflated during cold start.
 * Each pane is inflated on first use; until then it counts as "not visible".
 *
 * `xRoot` / `xOrNull` return null while the pane is still a stub. The child accessors
 * (cardAdmin*, device*, vpn*, refresh*, ...) inflate on demand, so only use them from
 * paths that are about to show the pane or that already know it is open. Passive paths
 * should go through `xOrNull`. The `onXInflated` hooks run once, right after inflation,
 * for listener wiring and for applying state that arrived while the pane was a stub.
 */
class BrowseLazyPanes(private val binding: ActivityBrowseBinding) {
    var onAdminInflated: ((BrowsePaneAdminBinding) -> Unit)? = null
    var onAboutInflated: ((BrowsePaneAboutBinding) -> Unit)? = null
    var onDeviceInflated: ((BrowsePaneDeviceBinding) -> Unit)? = null
    var onVpnInflated: ((BrowsePaneVpnBinding) -> Unit)? = null
    var onRefreshInflated: ((BrowsePaneRefreshBinding) -> Unit)? = null

    private var admin: BrowsePaneAdminBinding? = null
    private var about: BrowsePaneAboutBinding? = null
    private var device: BrowsePaneDeviceBinding? = null
    private var vpn: BrowsePaneVpnBinding? = null
    private var refresh: BrowsePaneRefreshBinding? = null

    val adminOrNull: BrowsePaneAdminBinding? get() = admin
    val aboutOrNull: BrowsePaneAboutBinding? get() = about
    val deviceOrNull: BrowsePaneDeviceBinding? get() = device
    val vpnOrNull: BrowsePaneVpnBinding? get() = vpn
    val refreshOrNull: BrowsePaneRefreshBinding? get() = refresh

    val adminRoot: View? get() = admin?.root
    val aboutRoot: View? get() = about?.root
    val deviceRoot: View? get() = device?.root
    val vpnRoot: View? get() = vpn?.root
    val refreshRoot: View? get() = refresh?.root

    fun ensureAdmin(): BrowsePaneAdminBinding {
        admin?.let { return it }
        val bound = BrowsePaneAdminBinding.bind(inflate(binding.adminStub))
        admin = bound
        onAdminInflated?.invoke(bound)
        return bound
    }

    fun ensureAbout(): BrowsePaneAboutBinding {
        about?.let { return it }
        val bound = BrowsePaneAboutBinding.bind(inflate(binding.aboutStub))
        about = bound
        onAboutInflated?.invoke(bound)
        return bound
    }

    fun ensureDevice(): BrowsePaneDeviceBinding {
        device?.let { return it }
        val bound = BrowsePaneDeviceBinding.bind(inflate(binding.deviceStub))
        device = bound
        onDeviceInflated?.invoke(bound)
        return bound
    }

    fun ensureVpn(): BrowsePaneVpnBinding {
        vpn?.let { return it }
        val bound = BrowsePaneVpnBinding.bind(inflate(binding.vpnStub))
        vpn = bound
        onVpnInflated?.invoke(bound)
        return bound
    }

    fun ensureRefresh(): BrowsePaneRefreshBinding {
        refresh?.let { return it }
        val bound = BrowsePaneRefreshBinding.bind(inflate(binding.refreshStub))
        refresh = bound
        onRefreshInflated?.invoke(bound)
        return bound
    }

    fun isAdminVisible(): Boolean = adminRoot?.visibility == View.VISIBLE
    fun isAboutVisible(): Boolean = aboutRoot?.visibility == View.VISIBLE
    fun isDeviceVisible(): Boolean = deviceRoot?.visibility == View.VISIBLE
    fun isVpnVisible(): Boolean = vpnRoot?.visibility == View.VISIBLE
    fun isRefreshVisible(): Boolean = refreshRoot?.visibility == View.VISIBLE

    /** True when any of the five setting panes is on screen. */
    fun isAnyVisible(): Boolean =
        isDeviceVisible() || isAboutVisible() || isAdminVisible() || isRefreshVisible() || isVpnVisible()

    /** Hides every pane that has been inflated; never inflates a stub. */
    fun hideAllSettingPanes() {
        admin?.root?.visibility = View.GONE
        about?.root?.visibility = View.GONE
        device?.root?.visibility = View.GONE
        vpn?.root?.visibility = View.GONE
        refresh?.root?.visibility = View.GONE
    }

    // Admin pane children
    val cardAdminRefresh get() = ensureAdmin().cardAdminRefresh
    val cardAdminWeb get() = ensureAdmin().cardAdminWeb
    val cardAdminEpg get() = ensureAdmin().cardAdminEpg
    val cardAdminVpn get() = ensureAdmin().cardAdminVpn
    val cardAdminAbout get() = ensureAdmin().cardAdminAbout
    val cardAdminFavorites get() = ensureAdmin().cardAdminFavorites
    val cardAdminSetup get() = ensureAdmin().cardAdminSetup
    val cardAdminVpnDetail get() = ensureAdmin().cardAdminVpnDetail
    val adminParentalSummary get() = ensureAdmin().adminParentalSummary
    val adminQuietToggle get() = ensureAdmin().adminQuietToggle
    val adminQuietSchedule get() = ensureAdmin().adminQuietSchedule
    val adminBackupLink get() = ensureAdmin().adminBackupLink

    // Device pane children
    val deviceBack get() = ensureDevice().deviceBack
    val deviceEmailValue get() = ensureDevice().deviceEmailValue
    val deviceIdValue get() = ensureDevice().deviceIdValue
    val deviceUrlValue get() = ensureDevice().deviceUrlValue
    val deviceRemovePlaylist get() = ensureDevice().deviceRemovePlaylist

    // VPN pane children (VpnUi owns the rest through BrowsePaneVpnBinding)
    val vpnBack get() = ensureVpn().vpnBack

    // Refresh pane children
    val refreshBack get() = ensureRefresh().refreshBack
    val refreshNowCard get() = ensureRefresh().refreshNowCard
    val refreshIntervalCard get() = ensureRefresh().refreshIntervalCard
    val refreshIntervalValue get() = ensureRefresh().refreshIntervalValue
    val refreshStatus get() = ensureRefresh().refreshStatus

    private fun inflate(stub: ViewStub): View = stub.inflate()
}

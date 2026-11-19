package com.olympus.iptv.vpn

import android.content.Intent
import android.net.VpnService
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.R
import com.olympus.iptv.databinding.ActivityBrowseBinding
import com.olympus.iptv.ui.SoftKeyboard
import java.io.InterruptedIOException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnUi(
    private val activity: AppCompatActivity,
    private val binding: ActivityBrowseBinding,
    private val askConsent: (Intent) -> Unit
) {
    private var provider = ""
    private var account: VpnAccount? = null
    private var selectedId = ""
    private var backupId = ""
    private var pickingBackup = false
    private var query = ""
    private var busy = false
    private var job: Job? = null
    private val cities = ArrayList<VpnCity>()
    private val shown = ArrayList<VpnCity>()
    private val adapter = CityAdapter()
    private val listener: (TunnelState) -> Unit = { state ->
        activity.runOnUiThread {
            if (!activity.isDestroyed) render(state)
        }
    }

    init {
        binding.vpnCities.layoutManager = LinearLayoutManager(activity)
        binding.vpnCities.adapter = adapter
        SoftKeyboard.showWhenFocused(activity, binding.vpnUser)
        SoftKeyboard.showWhenFocused(activity, binding.vpnPass)
        SoftKeyboard.showWhenFocused(activity, binding.vpnCode)
        SoftKeyboard.showWhenFocused(activity, binding.vpnConfig)
        SoftKeyboard.showWhenFocused(activity, binding.vpnCityFind)
        binding.vpnPia.setOnClickListener { choose("pia") }
        binding.vpnProton.setOnClickListener { choose("proton") }
        binding.vpnSurfshark.setOnClickListener { choose("surfshark") }
        binding.vpnNord.setOnClickListener { choose("nord") }
        binding.vpnWireguard.setOnClickListener { choose("wireguard") }
        binding.vpnAuth.setOnClickListener { authenticate() }
        binding.vpnCheck.setOnClickListener { checkProfile() }
        binding.vpnConnect.setOnClickListener { connect() }
        binding.vpnBackup.setOnClickListener {
            if (cities.isEmpty()) return@setOnClickListener
            pickingBackup = true
            status(activity.getString(R.string.vpn_backup_pick))
        }
        binding.vpnCityFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                showFiltered()
            }
        })
    }

    fun start() {
        AppTunnel.addListener(listener)
        render(AppTunnel.state)
    }

    fun stop() {
        AppTunnel.removeListener(listener)
    }

    fun onShow() {
        val saved = VpnStore.load(activity)
        if (!saved?.username.isNullOrBlank() && binding.vpnUser.text.isNullOrBlank()) {
            binding.vpnUser.setText(saved?.username)
        }
        render(AppTunnel.state)
        binding.vpnBack.post { binding.vpnBack.requestFocus() }
    }

    fun denied() {
        VpnStore.setConnected(activity, false)
        status(activity.getString(R.string.vpn_denied), failed = true)
    }

    private fun choose(id: String) {
        job?.cancel()
        busy = false
        provider = id
        account = null
        selectedId = ""
        query = ""
        cities.clear()
        shown.clear()
        binding.vpnCityFind.setText("")
        adapter.notifyDataSetChanged()
        markProviders()
        val profile = id == "wireguard"
        binding.vpnAccount.visibility = if (profile) View.GONE else View.VISIBLE
        binding.vpnConfigBox.visibility = if (profile) View.VISIBLE else View.GONE
        binding.vpnCodeRow.visibility = if (id == "proton") View.VISIBLE else View.GONE
        binding.vpnCities.visibility = View.GONE
        binding.vpnCityFind.visibility = View.GONE
        binding.vpnPass.hint = activity.getString(if (id == "nord") R.string.vpn_nord_pass else R.string.vpn_pass)
        val saved = VpnStore.load(activity)
        val again = saved?.provider == id && saved.toConfig() != null
        if (AppTunnel.state is TunnelState.On || AppTunnel.state is TunnelState.Connecting || AppTunnel.state is TunnelState.Reconnecting) {
            binding.vpnConnect.visibility = View.VISIBLE
            binding.vpnConnect.setText(R.string.vpn_disconnect)
        } else if (again) {
            binding.vpnConnect.visibility = View.VISIBLE
            binding.vpnConnect.setText(R.string.vpn_again)
        } else {
            binding.vpnConnect.visibility = View.GONE
        }
        val focus = if (profile) binding.vpnConfig else binding.vpnUser
        focus.post { focus.requestFocus() }
    }

    private fun authenticate() {
        val user = binding.vpnUser.text?.toString()?.trim().orEmpty()
        val pass = binding.vpnPass.text?.toString().orEmpty()
        if (user.isBlank() || pass.isBlank()) {
            status(activity.getString(R.string.vpn_need_login), failed = true)
            return
        }
        val code = binding.vpnCode.text?.toString()?.trim().orEmpty()
        val chosen = provider
        job?.cancel()
        busy = true
        status(activity.getString(R.string.vpn_signing_in))
        job = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (chosen) {
                        "pia" -> PiaClient.signIn(activity, user, pass)
                        "proton" -> ProtonClient.signIn(user, pass, code)
                        "surfshark" -> SurfsharkClient.signIn(user, pass)
                        "nord" -> NordClient.signIn(user, pass)
                        else -> throw VpnAuthException(activity.getString(R.string.vpn_refused))
                    }
                }
            }
            if (!isActive || activity.isDestroyed) return@launch
            busy = false
            result.onSuccess { signed ->
                account = signed
                VpnDesk.account = signed
                VpnDesk.provider = chosen
                showCities(signed.cities)
            }
            result.onFailure { error -> status(friendly(error), failed = true) }
        }
    }

    private fun checkProfile() {
        val parsed = runCatching { parseWireGuard(binding.vpnConfig.text?.toString().orEmpty()) }
        val config = parsed.getOrElse { error ->
            status(friendly(error), failed = true)
            return
        }
        account = VpnAccount(listOf(VpnCity("profile", config.label))) { config }
        VpnDesk.account = account
        VpnDesk.provider = "wireguard"
        showCities(account?.cities.orEmpty())
    }

    private fun showCities(rows: List<VpnCity>) {
        cities.clear()
        cities.addAll(rows)
        selectedId = ""
        pickingBackup = false
        backupId = VpnStore.load(activity)?.backupCityId.orEmpty()
        val backupName = rows.firstOrNull { it.id == backupId }?.name
        binding.vpnBackup.setText(
            if (backupName.isNullOrBlank()) activity.getString(R.string.vpn_backup)
            else activity.getString(R.string.vpn_backup_set, backupName)
        )
        query = ""
        binding.vpnCityFind.setText("")
        showFiltered()
        binding.vpnConnect.visibility = View.GONE
        binding.vpnBackup.visibility = if (rows.size > 1) View.VISIBLE else View.GONE
        if (rows.isEmpty()) {
            binding.vpnCities.visibility = View.GONE
            binding.vpnCityFind.visibility = View.GONE
            status(activity.getString(R.string.vpn_refused), failed = true)
            return
        }
        binding.vpnCityFind.visibility = View.VISIBLE
        binding.vpnCities.visibility = View.VISIBLE
        status(activity.getString(R.string.vpn_need_city))
        binding.vpnCities.post {
            binding.vpnCities.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun showFiltered() {
        val savedId = VpnStore.load(activity)?.cityId
        val needle = query.trim()
        val matched = if (needle.isEmpty()) cities else cities.filter { it.name.contains(needle, ignoreCase = true) }
        shown.clear()
        shown.addAll(preferCity(matched, savedId))
        adapter.notifyDataSetChanged()
    }

    private fun connect() {
        if (AppTunnel.state is TunnelState.On || AppTunnel.state is TunnelState.Connecting || AppTunnel.state is TunnelState.Reconnecting) {
            AppTunnel.disconnect(activity)
            status(activity.getString(R.string.vpn_status_off))
            binding.vpnConnect.setText(R.string.vpn_again)
            return
        }
        val config = runCatching {
            val current = account
            if (selectedId.isNotBlank() && current != null) current.config(selectedId)
            else {
                val saved = VpnStore.load(activity)
                if (saved?.provider == provider) saved.toConfig() else null
            }
        }.getOrElse { error ->
            status(friendly(error), failed = true)
            return
        }
        if (config == null) {
            status(activity.getString(R.string.vpn_need_city))
            return
        }
        val saved = VpnStore.load(activity) ?: SavedVpn()
        saved.provider = config.provider.ifBlank { provider }
        saved.label = config.label
        if (selectedId.isNotBlank()) saved.cityId = selectedId
        val typedUser = binding.vpnUser.text?.toString()?.trim().orEmpty()
        if (typedUser.isNotBlank()) saved.username = typedUser
        val typedPass = binding.vpnPass.text?.toString().orEmpty()
        if (typedPass.isNotBlank()) saved.password = typedPass
        saved.privateKey = config.privateKey
        saved.address = config.address
        saved.dns = config.dns
        saved.peerKey = config.peerKey
        saved.presharedKey = config.presharedKey
        saved.endpointHost = config.endpointHost
        saved.endpointPort = config.endpointPort
        if (backupId.isNotBlank() && backupId != selectedId) {
            val alternate = runCatching { account?.config(backupId) }.getOrElse { error ->
                status(friendly(error), failed = true)
                return
            }
            if (alternate != null) saved.storeBackup(backupId, alternate)
        } else if (backupId.isBlank()) {
            saved.clearBackup()
        }
        saved.connected = true
        runCatching { VpnStore.save(activity, saved) }.onFailure {
            status(activity.getString(R.string.vpn_refused), failed = true)
            return
        }
        SoftKeyboard.hide(activity)
        val consent = VpnService.prepare(activity)
        if (consent != null) askConsent(consent) else AppTunnel.connect(activity)
        status(activity.getString(R.string.vpn_connecting))
        binding.vpnConnect.visibility = View.VISIBLE
        binding.vpnConnect.setText(R.string.vpn_disconnect)
    }

    private fun render(state: TunnelState) {
        if (busy && state is TunnelState.Off) return
        when (state) {
            is TunnelState.Off -> status(activity.getString(R.string.vpn_status_off))
            is TunnelState.Connecting -> {
                status(activity.getString(R.string.vpn_connecting))
                binding.vpnConnect.visibility = View.VISIBLE
                binding.vpnConnect.setText(R.string.vpn_disconnect)
            }
            is TunnelState.Reconnecting -> {
                status(activity.getString(R.string.vpn_reconnecting, state.label))
                binding.vpnConnect.visibility = View.VISIBLE
                binding.vpnConnect.setText(R.string.vpn_disconnect)
            }
            is TunnelState.On -> {
                status(activity.getString(R.string.vpn_connected, state.label), up = true)
                binding.vpnConnect.visibility = View.VISIBLE
                binding.vpnConnect.setText(R.string.vpn_disconnect)
            }
            is TunnelState.Failed -> {
                status(state.message, failed = true)
                if (binding.vpnConnect.visibility == View.VISIBLE) binding.vpnConnect.setText(R.string.connect)
            }
        }
    }

    private fun markProviders() {
        binding.vpnPia.isSelected = provider == "pia"
        binding.vpnProton.isSelected = provider == "proton"
        binding.vpnSurfshark.isSelected = provider == "surfshark"
        binding.vpnNord.isSelected = provider == "nord"
        binding.vpnWireguard.isSelected = provider == "wireguard"
    }

    private fun status(text: String, up: Boolean = false, failed: Boolean = false) {
        binding.vpnStatus.text = text
        binding.vpnStatus.setTextColor(
            activity.getColor(
                when {
                    up -> R.color.accent
                    failed -> R.color.danger
                    else -> R.color.text_secondary
                }
            )
        )
    }

    private fun friendly(error: Throwable): String {
        if (error is VpnAuthException) return error.message ?: activity.getString(R.string.vpn_refused)
        if (error is InterruptedIOException || error is UnknownHostException) return activity.getString(R.string.vpn_no_answer)
        return activity.getString(R.string.vpn_refused)
    }

    private inner class CityAdapter : RecyclerView.Adapter<CityAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vpn_city, parent, false) as TextView
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.label.text = shown[position].name
            holder.label.isSelected = shown[position].id == selectedId || shown[position].id == backupId
            holder.label.nextFocusLeftId = R.id.btnAdmin
            holder.label.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION || index !in shown.indices) return@setOnClickListener
                if (pickingBackup) {
                    if (shown[index].id == selectedId) {
                        status(activity.getString(R.string.vpn_backup_same))
                        return@setOnClickListener
                    }
                    backupId = shown[index].id
                    pickingBackup = false
                    binding.vpnBackup.setText(activity.getString(R.string.vpn_backup_set, shown[index].name))
                    notifyDataSetChanged()
                    status(activity.getString(R.string.vpn_backup_set, shown[index].name))
                    return@setOnClickListener
                }
                val previous = selectedId
                selectedId = shown[index].id
                val oldIndex = shown.indexOfFirst { it.id == previous }
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
                notifyItemChanged(index)
                binding.vpnConnect.visibility = View.VISIBLE
                binding.vpnConnect.setText(R.string.connect)
            }
        }

        override fun getItemCount(): Int = shown.size

        inner class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
    }
}

package com.olympus.iptv.ui.browse

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Survives configuration changes and returns from Player / Library so Browse
 * can restore section, group, scroll, and EPG focus without re-querying SQLite.
 */
class BrowseViewModel : ViewModel() {
    data class GuideUiState(
        val sectionKey: String = "live",
        val selectedCategoryId: String? = null,
        val selectedCategoryName: String = "",
        val guideMenu: String = "groups",
        val categoryScroll: Int = 0,
        val channelScroll: Int = 0,
        val programScrollX: Int = 0,
        val programRow: Int = 0,
        val playlistId: String? = null,
        val focusedChannelId: String? = null,
        val focusedProgramTitle: String? = null,
        val epgWindowStart: Long = 0L,
        val epgWindowEnd: Long = 0L
    )

    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state.asStateFlow()

    fun snapshot(): GuideUiState = _state.value

    fun rememberPlaylist(id: String?) {
        _state.update { it.copy(playlistId = id) }
    }

    fun rememberSection(key: String) {
        _state.update { it.copy(sectionKey = key) }
    }

    fun rememberCategory(id: String?, name: String) {
        _state.update { it.copy(selectedCategoryId = id, selectedCategoryName = name) }
    }

    fun rememberMenu(menu: String) {
        _state.update { it.copy(guideMenu = menu) }
    }

    fun rememberScrolls(
        categoryScroll: Int? = null,
        channelScroll: Int? = null,
        programScrollX: Int? = null,
        programRow: Int? = null
    ) {
        _state.update {
            it.copy(
                categoryScroll = categoryScroll ?: it.categoryScroll,
                channelScroll = channelScroll ?: it.channelScroll,
                programScrollX = programScrollX ?: it.programScrollX,
                programRow = programRow ?: it.programRow
            )
        }
    }

    fun rememberFocused(channelId: String?, programTitle: String?) {
        _state.update {
            it.copy(focusedChannelId = channelId, focusedProgramTitle = programTitle)
        }
    }

    fun rememberEpgWindow(start: Long, end: Long) {
        _state.update { it.copy(epgWindowStart = start, epgWindowEnd = end) }
    }
}

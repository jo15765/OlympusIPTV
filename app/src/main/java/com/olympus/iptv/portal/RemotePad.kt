package com.olympus.iptv.portal

import kotlinx.coroutines.flow.MutableSharedFlow

/** Commands posted by the phone dashboard and read by the player or guide. */
object RemotePad {
    data class Command(val action: String, val text: String = "")

    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 16)

    fun post(action: String, text: String = "") {
        commands.tryEmit(Command(action.trim().lowercase(), text))
    }
}

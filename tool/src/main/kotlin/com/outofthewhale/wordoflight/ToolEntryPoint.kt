package com.outofthewhale.wordoflight

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        // Nothing to register. Word of Light reads from the device, so it has
        // no app server and wants no push credentials.
    }

    override suspend fun onPushNotification(data: ByteArray) {
        // Unused - the tool never subscribes to push.
    }
}

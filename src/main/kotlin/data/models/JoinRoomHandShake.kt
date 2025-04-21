package com.eyeshield.data.models

import com.eyeshield.utils.Constants.TYPE_JOIN_ROOM_HANDSHAKE

data class JoinRoomHandShake(
    val username: String,
    val roomName: String,
    val clientId: String
): BaseModel(TYPE_JOIN_ROOM_HANDSHAKE)

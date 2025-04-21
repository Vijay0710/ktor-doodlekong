package com.eyeshield.data.models

import com.eyeshield.utils.Constants.TYPE_CHAT_MESSAGE

data class ChatMessage(
    val from: String,
    val roomName: String,
    val message: String,
    val timestamp: Long
): BaseModel(type = TYPE_CHAT_MESSAGE)

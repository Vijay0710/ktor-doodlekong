package com.eyeshield.data.models

import com.eyeshield.utils.Constants.TYPE_ANNOUNCEMENT_DATA

data class Announcement(
    val message: String,
    val timestamp: Long,
    val announcementType: Int
): BaseModel(TYPE_ANNOUNCEMENT_DATA) {
    companion object {
        const val TYPE_PLAYER_GUESSED_WORD = 0
        const val TYPE_PLAYER_JOINED = 1
        const val TYPE_PLAYER_LEFT = 2
        const val TYPE_EVERYBODY_GUESSED_IT = 3
    }
}

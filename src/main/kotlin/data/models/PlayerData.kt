package com.eyeshield.data.models

data class PlayerData(
    val username: String,
    var drawing: Boolean = false,
    var score: Int = 0,
    var rank: Int = 0
)

package com.eyeshield.data.models

import com.eyeshield.data.Room
import com.eyeshield.utils.Constants.TYPE_PHASE_CHANGE


data class PhaseChange(
    var phase: Room.Phase?,
    var time: Long,
    val drawingPlayer: String? = null
): BaseModel(TYPE_PHASE_CHANGE)

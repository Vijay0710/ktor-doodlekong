package com.eyeshield.data.models

import com.eyeshield.utils.Constants.TYPE_CURRENT_ROUND_DRAW_INFO

data class RoundDrawInfo(
    val data: List<String>
): BaseModel(TYPE_CURRENT_ROUND_DRAW_INFO)

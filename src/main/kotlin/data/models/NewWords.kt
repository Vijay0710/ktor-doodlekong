package com.eyeshield.data.models

import com.eyeshield.utils.Constants.TYPE_NEW_WORDS

data class NewWords(
    val newWords: List<String>,
): BaseModel(TYPE_NEW_WORDS)

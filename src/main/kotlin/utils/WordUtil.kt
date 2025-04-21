package com.eyeshield.utils

import java.io.File

val words = readWordList("resources/wordlist.txt")

fun readWordList(fileName: String): List<String> {
    val inputStream = File(fileName).inputStream()
    val words = mutableListOf<String>()
    inputStream.bufferedReader().forEachLine {
        words.add(it)
    }
    return words
}

fun getRandomWords(amount: Int): List<String> {
    var currAmount = 0
    val result = mutableListOf<String>()

    while (currAmount < amount) {
        val word = words.random()
        if(!result.contains(word)) {
            result.add(word)
            currAmount++
        }
    }
    return result
}



/**
 * Example:
 *
 * ```
 * apple juice
 * _____ _____  (what map does)
 * _ _ _ _ _  _ _ _ _ _ (joinToString separates each character by space)
 * Note: if there is already a space it adds one more space
 * ```
 * **/
fun String.transformToUnderScores() =
    toCharArray().map {
        if(it != ' ') '_' else ' '
    }.joinToString(" ")
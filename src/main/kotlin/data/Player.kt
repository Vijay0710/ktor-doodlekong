package com.eyeshield.data

import com.eyeshield.data.models.Ping
import com.eyeshield.gson
import com.eyeshield.server
import com.eyeshield.utils.Constants.PING_FREQUENCY
import io.ktor.websocket.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Player(
    val username: String,
    var socket: WebSocketSession,
    val clientId: String,
    var isDrawing: Boolean = false,
    var score: Int = 0,
    var rank: Int = 0
) {
    private var pingJob: Job? = null
    private var pingTime = 0L
    private var pongTime = 0L

    var isOnline = true

    // Every 3 seconds we will be sending a ping to the client, and he will reply back with a pong
    // if the difference between ping and pong is more than 3 seconds we will close the websocket connection with the associated player

    fun startPinging() {
        pingJob?.cancel()
        pingJob = GlobalScope.launch {
            while (true) {
                sendPing()
                println("Pinging to the Client")
                delay(PING_FREQUENCY)
            }
        }
    }

    private suspend fun sendPing() {
        pingTime = System.currentTimeMillis()
        socket.send(Frame.Text(gson.toJson(Ping())))
        delay(PING_FREQUENCY)
        if (pingTime - pongTime > PING_FREQUENCY) {
            isOnline = false
            server.playerLeft(clientId)
            pingJob?.cancel()
        }
    }

    fun receivedPong() {
        pongTime = System.currentTimeMillis()
        isOnline = true

        println("Received Pong")
    }

    fun disconnect() {
        pingJob?.cancel()
    }
}

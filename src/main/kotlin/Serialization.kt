package com.eyeshield

import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.slf4j.event.*

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        gson {
        }
    }
}

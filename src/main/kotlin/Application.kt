package com.eyeshield

import com.eyeshield.routes.createRoomRoute
import com.eyeshield.routes.gameWebSocketRoute
import com.eyeshield.routes.getRoomsRoute
import com.eyeshield.routes.joinRoomRoute
import com.eyeshield.session.DrawingSession
import com.google.gson.Gson
import io.ktor.server.application.*
import io.ktor.server.application.ApplicationCallPipeline.ApplicationPhase.Plugins
import io.ktor.server.application.ApplicationCallPipeline.ApplicationPhase.Setup
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*

val server = DrawingServer()
val gson = Gson()

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {

    install(Sessions) {
        cookie<DrawingSession>("SESSION")
    }
    intercept(Plugins) {
        if (!call.response.isCommitted && call.sessions.get<DrawingSession>() == null) {
            val clientId = call.parameters["client_id"] ?: ""
            call.sessions.set(
                DrawingSession(
                    clientId = clientId,
                    sessionId = generateNonce()
                )
            )
            print("Drawing session is set ${call.sessions.get<DrawingSession>()}")
        }
    }

    configureSockets()

    install(RoutingRoot) {
        createRoomRoute()
        getRoomsRoute()
        joinRoomRoute()
        gameWebSocketRoute()
    }

    configureMonitoring()
    configureSerialization()
}

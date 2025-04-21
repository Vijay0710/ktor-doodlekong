package com.eyeshield.routes

import com.eyeshield.data.models.Ping
import com.eyeshield.data.Player
import com.eyeshield.data.Room
import com.eyeshield.data.models.*
import com.eyeshield.gson
import com.eyeshield.server
import com.eyeshield.session.DrawingSession
import com.eyeshield.utils.Constants.TYPE_ANNOUNCEMENT_DATA
import com.eyeshield.utils.Constants.TYPE_CHAT_MESSAGE
import com.eyeshield.utils.Constants.TYPE_CHOSEN_WORD
import com.eyeshield.utils.Constants.TYPE_DRAW_DATA
import com.eyeshield.utils.Constants.TYPE_GAME_STATE
import com.eyeshield.utils.Constants.TYPE_JOIN_ROOM_HANDSHAKE
import com.eyeshield.utils.Constants.TYPE_PHASE_CHANGE
import com.eyeshield.utils.Constants.TYPE_PING
import com.google.gson.JsonParser
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import com.eyeshield.utils.Constants.PING_FREQUENCY
import com.eyeshield.utils.Constants.TYPE_DISCONNECT_REQUEST
import com.eyeshield.utils.Constants.TYPE_DRAW_ACTION

/**
 * A wrapper for different WebSocket Connection to use in routes
 * **/
fun Route.standardWebSocket(
    handleFrame: suspend (
        socket: DefaultWebSocketServerSession,
        clientId: String,
        message: String,
        payload: BaseModel
    ) -> Unit
) {
    webSocket {
        val session = call.sessions.get<DrawingSession>()

        print("Session is : $session")

        if(session == null) {
            close(
                reason = CloseReason(
                    CloseReason.Codes.VIOLATED_POLICY,
                    "No Session"
                )
            )
            return@webSocket
        }

        try {
            // Suspends as long as connection is open
            incoming.consumeEach { frame ->
                if(frame is Frame.Text) {
                    val message = frame.readText()
                    val jsonObject = JsonParser.parseString(message).asJsonObject

                    val type = when(jsonObject.get("type").asString) {
                        TYPE_CHAT_MESSAGE -> ChatMessage::class.java
                        TYPE_DRAW_DATA -> DrawData::class.java
                        TYPE_ANNOUNCEMENT_DATA -> Announcement::class.java
                        TYPE_JOIN_ROOM_HANDSHAKE -> JoinRoomHandShake::class.java
                        TYPE_PHASE_CHANGE -> PhaseChange::class.java
                        TYPE_CHOSEN_WORD -> ChosenWord::class.java
                        TYPE_GAME_STATE -> GameState::class.java
                        TYPE_PING -> Ping::class.java
                        TYPE_DISCONNECT_REQUEST -> DisconnectRequest::class.java
                        TYPE_DRAW_ACTION -> DrawAction::class.java
                        else -> BaseModel::class.java
                    }
                    val payload = gson.fromJson(message, type)
                    handleFrame(this, session.clientId, message, payload)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Handle disconnects
            val playerWithClientId = server.getRoomWithClientId(session.clientId)?.players?.find {
                it.clientId == session.clientId
            }

            if(playerWithClientId != null) {
                server.playerLeft(session.clientId, false)
            }

        }
    }
}

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        standardWebSocket { socket, clientId, message, payload ->
            when(payload) {

                is JoinRoomHandShake -> {
                    val room = server.rooms[payload.roomName]

                    if(room == null){
                        val gameError = GameError(GameError.ERROR_ROOM_NOT_FOUND)
                        socket.send(Frame.Text(gson.toJson(gameError)))
                        return@standardWebSocket
                    }

                    print("In Join Room handshake phase: $payload")

                    val player = Player(
                        username = payload.username,
                        socket = socket,
                        clientId = payload.clientId
                    )

                    server.playerJoined(player)

                    if(!room.containsPlayer(player.username)) {
                        room.addPlayer(player.clientId, player.username, socket)
                    }
                    /** If the disconnect between client and server is greater than ping frequency then we need to make sure that we assign new socket connection to that player
                    * The below condition is handled because if it is greater than Ping Frequency we set isOnline flag to false
                    * When isOnline is set to false we remove him from players list (This will be only done if [PING_FREQUENCY] > 3 seconds **/
                    else {
                        val playerInRoom = room.players.find { it.clientId == clientId }
                        playerInRoom?.socket = socket
                        playerInRoom?.startPinging()
                    }
                }

                is DrawData -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if(room.phase == Room.Phase.GAME_RUNNING) {
                        room.broadcastToAllExcept(message, clientId)
                        room.addSerializedDrawInfo(message)
                    }
                    room.lastDrawDat = payload
                }

                is DrawAction -> {
                    val room = server.getRoomWithClientId(clientId) ?: return@standardWebSocket
                    room.broadcastToAllExcept(message, clientId)
                    room.addSerializedDrawInfo(message)
                }

                is ChosenWord -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    room.setWordAndSwitchToGameRunning(payload.chosenWord)
                }

                is ChatMessage -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if(!room.checkWordAndNotifyPlayers(payload)) {
                        room.broadcast(message)
                    }
                }

                is Ping -> {
                    server.players[clientId]?.receivedPong()
                }

                is DisconnectRequest -> {
                    server.playerLeft(clientId, true)
                }
            }
        }
    }
}
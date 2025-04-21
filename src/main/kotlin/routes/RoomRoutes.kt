package com.eyeshield.routes

import com.eyeshield.data.Room
import com.eyeshield.data.models.BasicApiResponse
import com.eyeshield.data.models.CreateRoomRequest
import com.eyeshield.data.models.RoomResponse
import com.eyeshield.server
import com.eyeshield.utils.Constants.MAX_ROOM_SIZE
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.logging.Logger

fun Route.createRoomRoute() {
    route("/api/createRoom") {
        post {

            val roomRequest = runCatching {
                call.receiveNullable<CreateRoomRequest>()
            }.getOrNull()

            if (roomRequest == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            if (server.rooms[roomRequest.name] != null) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "Room Already Exists")
                )
                return@post
            }

            if (roomRequest.maximumPlayers < 2) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "The minimum room size is 2")
                )
                return@post
            }

            if (roomRequest.maximumPlayers > MAX_ROOM_SIZE) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "The maximum room size is $MAX_ROOM_SIZE")
                )
                return@post
            }

            val room = Room(
                roomRequest.name,
                roomRequest.maximumPlayers
            )

            server.rooms[roomRequest.name] = room

            println("Room created: ${roomRequest.name}")

            call.respond(HttpStatusCode.OK, BasicApiResponse(true))
        }
    }
}

fun Route.getRoomsRoute() {

    println("Entered get rooms route")

    route("/api/getRooms") {
        get {
            val searchQuery = call.parameters["searchQuery"]

            println("Search query is $searchQuery")

            if (searchQuery == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val roomsResult = server.rooms.filterKeys {
                it.contains(searchQuery, ignoreCase = true)
            }

            roomsResult.forEach {
                println("${it.key}: ${it.value.name}, ${it.value.maximumPlayers}")
            }

            val roomResponses = roomsResult.values.map {
                RoomResponse(
                    it.name,
                    it.maximumPlayers,
                    it.players.size
                )
            }.sortedBy {
                it.name
            }

            call.respond(HttpStatusCode.OK, roomResponses)
        }
    }
}


fun Route.joinRoomRoute() {
    route("/api/joinRoom") {
        get {
            val username = call.parameters["username"]
            val roomName = call.parameters["roomName"]

            if (username == null || roomName == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val room = server.rooms[roomName]

            when {
                room == null -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(
                            false,
                            "Room not found"
                        )
                    )
                }

                room.containsPlayer(username) -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(
                            false,
                            "A Player with this username already joined"
                        )
                    )
                }

                room.players.size >= room.maximumPlayers -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(
                            false,
                            "This room is already full"
                        )
                    )
                }

                else -> {
                    call.respond(
                        HttpStatusCode.OK,
                        BasicApiResponse(true)
                    )
                }
            }
        }
    }
}
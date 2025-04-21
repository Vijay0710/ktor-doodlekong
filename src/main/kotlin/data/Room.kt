@file:OptIn(DelicateCoroutinesApi::class)

package com.eyeshield.data

import com.eyeshield.data.models.*
import com.eyeshield.gson
import com.eyeshield.other.matchesWord
import com.eyeshield.server
import com.eyeshield.utils.getRandomWords
import com.eyeshield.utils.transformToUnderScores
import com.eyeshield.utils.words
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class Room(
    val name: String,
    val maximumPlayers: Int,
    var players: List<Player> = listOf()
) {

    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var winningPlayers = listOf<String>()
    private var word: String? = null
    private var currWords: List<String>? = null
    private var drawingPlayerIndex = 0
    private var startTime = 0L

    private val playerRemoveJobs = ConcurrentHashMap<String, Job>()

    // Pair here determines the players object and player's index so when the player left we keep track of the index he
    // was initially inn when he was in the players list
    private val leftPlayers = ConcurrentHashMap<String, Pair<Player, Int>>()

    private var phaseChangedListener: ((Phase) -> Unit)? = null
    var phase = Phase.WAITING_FOR_PLAYERS
        set(value) {
            synchronized(field) {
                field = value
                phaseChangedListener?.let { change ->
                    change(value)
                }
            }
        }

    private var curRoundDrawData: List<String> = listOf()
    var lastDrawDat: DrawData? = null

    init {
        setPhaseChangedListener { newPhase ->
            when (newPhase) {
                Phase.WAITING_FOR_PLAYERS -> waitingForPlayers()
                Phase.WAITING_FOR_START -> waitingForStart()
                Phase.NEW_ROUND -> newRound()
                Phase.GAME_RUNNING -> gameRunning()
                Phase.SHOW_WORD -> showWords()
            }
        }
    }


    private fun setPhaseChangedListener(listener: (Phase) -> Unit) {
        phaseChangedListener = listener
    }

    private suspend fun sendCurrDrawInfoToPlayer(player: Player) {
        if (phase == Phase.GAME_RUNNING || phase == Phase.SHOW_WORD) {
            player.socket.send(Frame.Text(gson.toJson(RoundDrawInfo(curRoundDrawData))))
        }
    }

    fun addSerializedDrawInfo(drawAction: String) {
        curRoundDrawData = curRoundDrawData + drawAction
    }

    private suspend fun finishOffTheDrawing() {
        lastDrawDat?.let {
            // 2 corresponds to action move we are making sure that when player is drawing timer runs out or whatever the situation is if the last action is ACTION_MOVE we make sure to change that action to ACTION_UP
            // This is to indicate every non drawing player that last drawing point is where it should be for all the non drawing players
            if (curRoundDrawData.isNotEmpty() && it.motionEvent == 2) {
                val finishDrawData = it.copy(
                    motionEvent = 1
                )
                broadcast(gson.toJson(finishDrawData))
            }
        }
    }

    suspend fun addPlayer(clientId: String, username: String, socketSession: WebSocketSession): Player {


        // Functionality to reconnect left players
        var indexToAdd = players.size - 1

        val player = if (leftPlayers.contains(clientId)) {
            val leftPlayer = leftPlayers[clientId]
            leftPlayer?.first?.let {
                it.socket = socketSession
                it.isDrawing = drawingPlayer?.clientId == clientId
                indexToAdd = leftPlayer.second

                playerRemoveJobs[clientId]?.cancel()
                playerRemoveJobs.remove(clientId)
                leftPlayers.remove(clientId)
                it
            } ?:
            // Unreachable scenario added for null safety
            Player(
                username = username,
                clientId = clientId,
                socket = socketSession
            )
        } else {
            Player(
                username = username,
                clientId = clientId,
                socket = socketSession
            )
        }

        // If the player reconnects and his old list position exceeds current list size we make sure we add him to the end of the list to prevent index out of bounds
        indexToAdd = when {
            players.isEmpty() -> 0
            indexToAdd >= players.size -> players.size - 1
            else -> indexToAdd
        }

        val tempPlayers = players.toMutableList()
        tempPlayers.add(indexToAdd, player)

        players = tempPlayers.toList()

        if (players.size == 1) {
            phase = Phase.WAITING_FOR_PLAYERS
        } else if (players.size == 2 && phase == Phase.WAITING_FOR_PLAYERS) {
            phase = Phase.WAITING_FOR_START
            players = players.shuffled()
        } else if (phase == Phase.WAITING_FOR_START && players.size == maximumPlayers) {
            phase = Phase.NEW_ROUND
            players = players.shuffled()
        }

        val announcement = Announcement(
            "$username joined the party",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_JOINED
        )

        sendWordToPlayer(player)
        broadcastPlayerStates()
        sendCurrDrawInfoToPlayer(player)
        broadcast(gson.toJson(announcement))

        return player
    }

    fun removePlayer(clientId: String) {
        val player = players.find { it.clientId == clientId } ?: return
        val index = players.indexOf(player)

        leftPlayers[clientId] = player to index
        players -= player

        playerRemoveJobs[clientId] = GlobalScope.launch {
            delay(PLAYER_REMOVE_TIME)

            // We will give around 60 seconds to connect if he doesn't connect we will remove him from the list
            val playerToRemove = leftPlayers[clientId]
            leftPlayers.remove(clientId)

            // Removing from original players list
            playerToRemove?.let {
                players = players - it.first
            }
            playerRemoveJobs.remove(clientId)

        }

        val announcement = Announcement(
            "${player.username} has left the party :(",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_LEFT
        )

        GlobalScope.launch {
            broadcastPlayerStates()
            broadcast(gson.toJson(announcement))
            if (players.size == 1) {
                phase = Phase.WAITING_FOR_PLAYERS
                timerJob?.cancel()
            } else if (players.isEmpty()) {
                // We make sure players shouldn't be able to reconnect since all players left the room
                kill()
                server.rooms.remove(name)
            }
        }
    }

    private fun isGuessCorrect(guess: ChatMessage): Boolean {
        return guess.matchesWord(word ?: return false) && !winningPlayers.contains(guess.from) &&
                guess.from != drawingPlayer?.username && phase == Phase.GAME_RUNNING
    }

    suspend fun broadcast(message: String) {
        players.forEach { player ->
            if (player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    suspend fun broadcastToAllExcept(message: String, clientId: String) {
        players.filter {
            it.socket.isActive && it.clientId != clientId
        }.onEach {
            it.socket.send(Frame.Text(message))
        }
    }

    fun containsPlayer(username: String): Boolean {
        return players.find {
            it.username == username
        } != null
    }

    private fun waitingForPlayers() {
        GlobalScope.launch {
            val phaseChange = PhaseChange(
                Phase.WAITING_FOR_PLAYERS,
                DELAY_WAITING_FOR_START_TO_NEW_ROUND
            )

            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun waitingForStart() {
        GlobalScope.launch {
            timeAndNotify(DELAY_WAITING_FOR_START_TO_NEW_ROUND)
            val phaseChange = PhaseChange(
                Phase.WAITING_FOR_START,
                DELAY_WAITING_FOR_START_TO_NEW_ROUND
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun newRound() {
        curRoundDrawData = listOf()
        currWords = getRandomWords(3)
        val newWords = NewWords(currWords!!)
        nextDrawingPlayer()
        GlobalScope.launch {
            broadcastPlayerStates()
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(newWords)))
            timeAndNotify(DELAY_NEW_ROUND_TO_GAME_RUNNING)
        }
    }

    private fun gameRunning() {
        winningPlayers = listOf()
        val wordToSend = word ?: currWords?.random() ?: words.random()
        val wordWithUnderscores = wordToSend.transformToUnderScores()
        val drawingUserName = (drawingPlayer ?: players.random()).username
        val gameStateForDrawingPlayer = GameState(
            drawingUserName,
            wordToSend
        )
        val gameStateForGuessingPlayer = GameState(
            drawingUserName,
            wordWithUnderscores
        )
        GlobalScope.launch {
            broadcastToAllExcept(
                message = gson.toJson(gameStateForGuessingPlayer),
                clientId = drawingPlayer?.clientId ?: players.random().clientId
            )

            drawingPlayer?.socket?.send(
                Frame.Text(gson.toJson(gameStateForDrawingPlayer))
            )
            timeAndNotify(DELAY_GAME_RUNNING_TO_SHOW_WORD)

            println("Drawing Phase in room $name started. It'll last ${DELAY_GAME_RUNNING_TO_SHOW_WORD / 1000}s")
        }
    }

    fun setWordAndSwitchToGameRunning(word: String) {
        this.word = word
        phase = Phase.GAME_RUNNING
    }

    private fun showWords() {
        GlobalScope.launch {
            if (winningPlayers.isEmpty()) {
                drawingPlayer?.let {
                    it.score -= PENALTY_NOBODY_GUESSED_IT
                }
            }
            broadcastPlayerStates()
            word?.let {
                val chosenWord = ChosenWord(it, name)
                broadcast(gson.toJson(chosenWord))
            }

            timeAndNotify(DELAY_SHOW_WORD_TO_NEW_WORD)
            val phaseChange = PhaseChange(
                Phase.SHOW_WORD,
                DELAY_SHOW_WORD_TO_NEW_WORD
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun timeAndNotify(ms: Long) {
        timerJob?.cancel()
        timerJob = GlobalScope.launch {
            startTime = System.currentTimeMillis()
            val phaseChange = PhaseChange(
                phase,
                ms,
                drawingPlayer?.username
            )

            repeat((ms / UPDATE_TIME_FREQUENCY).toInt()) {
                if (it != 0) {
                    phaseChange.phase = null
                }
                broadcast(gson.toJson(phaseChange))
                phaseChange.time -= UPDATE_TIME_FREQUENCY
                delay(UPDATE_TIME_FREQUENCY)
            }

            phase = when (phase) {
                Phase.WAITING_FOR_START -> Phase.NEW_ROUND
                // This will only be executed if the player didn't choose a word in the given time limit
                Phase.NEW_ROUND -> {
                    word = null
                    Phase.GAME_RUNNING
                }
                Phase.GAME_RUNNING -> {
                    finishOffTheDrawing()
                    Phase.SHOW_WORD
                }
                Phase.SHOW_WORD -> Phase.NEW_ROUND
                else -> Phase.WAITING_FOR_PLAYERS
            }
        }
    }

    private fun addWinningPlayer(username: String): Boolean {
        winningPlayers = winningPlayers + username
        // The below indicates that all the players guessed it and -1 indicates except drawing player every other player guessed it
        if (winningPlayers.size == players.size - 1) {
            phase = Phase.NEW_ROUND
            return true
        }
        return false
    }

    suspend fun checkWordAndNotifyPlayers(message: ChatMessage): Boolean {
        if (isGuessCorrect(message)) {
            val guessingTime = System.currentTimeMillis() - startTime
            val timePercentageLeft = 1f - guessingTime.toFloat() / DELAY_GAME_RUNNING_TO_SHOW_WORD
            val score = GUESS_SCORE_DEFAULT + GUESS_SCORE_PERCENTAGE_MULTIPLIER * timePercentageLeft
            val player = players.find { it.username == message.from }

            player?.let {
                it.score += score.toInt()
            }
            drawingPlayer?.let {
                it.score += GUESS_SCORE_FOR_DRAWING_PLAYER / players.size
            }

            broadcastPlayerStates()

            val announcement = Announcement(
                "${message.from} has guessed it!",
                System.currentTimeMillis(),
                Announcement.TYPE_PLAYER_GUESSED_WORD
            )
            broadcast(gson.toJson(announcement))
            val isRoundOver = addWinningPlayer(message.from)

            if (isRoundOver) {
                val roundOverAnnouncement = Announcement(
                    "Everybody guessed it! New Round is starting...",
                    System.currentTimeMillis(),
                    Announcement.TYPE_EVERYBODY_GUESSED_IT
                )
                broadcast(gson.toJson(roundOverAnnouncement))
            }
            return true
        }
        return false
    }

    private suspend fun broadcastPlayerStates() {
        val playersList = players.sortedByDescending { it.score }.map {
            PlayerData(
                username = it.username,
                drawing = it.isDrawing,
                score = it.score,
                rank = it.rank
            )
        }

        playersList.forEachIndexed { index, playerData ->
            playerData.rank = index + 1
        }
        broadcast(gson.toJson(PlayersList(playersList)))
    }

    /**
     * This will be called whenever a new player joins a room it never matters if the player reconnects
     *
     * We will only send the visible word to the drawing player or when the Phase is [Phase.SHOW_WORD] or else we will
     * send transformed word
     * **/
    private suspend fun sendWordToPlayer(player: Player) {
        val delay = when (phase) {
            Phase.WAITING_FOR_PLAYERS -> 0L
            Phase.WAITING_FOR_START -> DELAY_WAITING_FOR_START_TO_NEW_ROUND
            Phase.NEW_ROUND -> DELAY_NEW_ROUND_TO_GAME_RUNNING
            Phase.GAME_RUNNING -> DELAY_GAME_RUNNING_TO_SHOW_WORD
            Phase.SHOW_WORD -> DELAY_SHOW_WORD_TO_NEW_WORD
        }

        val phaseChange = PhaseChange(
            phase,
            delay,
            drawingPlayer?.username
        )

        word?.let { currWord ->
            drawingPlayer?.let { drawingPlayer ->
                val gameState = GameState(
                    drawingPlayer = drawingPlayer.username,
                    word = if (player.isDrawing || phase == Phase.SHOW_WORD) {
                        currWord
                    } else {
                        currWord.transformToUnderScores()
                    }
                )
                player.socket.send(Frame.Text(gson.toJson(gameState)))
            }
        }
        player.socket.send(Frame.Text(gson.toJson(phaseChange)))
    }

    private fun nextDrawingPlayer() {
        drawingPlayer?.isDrawing = false

        if (players.isEmpty()) {
            return
        }

        drawingPlayer = if (drawingPlayerIndex <= players.size - 1) {
            players[drawingPlayerIndex]
        } else players.last()

        if (drawingPlayerIndex < players.size - 1)
            drawingPlayerIndex += 1
        else
            drawingPlayerIndex = 0
    }

    private fun kill() {
        playerRemoveJobs.values.forEach {
            it.cancel()
        }
        timerJob?.cancel()
    }

    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        SHOW_WORD
    }

    companion object {
        const val UPDATE_TIME_FREQUENCY = 1000L

        const val PLAYER_REMOVE_TIME = 60000L

        const val DELAY_WAITING_FOR_START_TO_NEW_ROUND = 10000L
        const val DELAY_NEW_ROUND_TO_GAME_RUNNING = 20000L
        const val DELAY_GAME_RUNNING_TO_SHOW_WORD = 60000L
        const val DELAY_SHOW_WORD_TO_NEW_WORD = 10000L

        const val PENALTY_NOBODY_GUESSED_IT = 50
        const val GUESS_SCORE_DEFAULT = 50
        const val GUESS_SCORE_PERCENTAGE_MULTIPLIER = 50
        const val GUESS_SCORE_FOR_DRAWING_PLAYER = 50
    }
}
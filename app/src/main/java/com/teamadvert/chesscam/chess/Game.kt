package com.teamadvert.chesscam.chess

import kotlin.math.abs

enum class GameStatus { ONGOING, CHECKMATE, STALEMATE, REPETITION, FIFTY_MOVES, INSUFFICIENT_MATERIAL }

/** A game from a starting position, with undo and result detection. */
class Game(val startFen: String = START_FEN) {
    private val positions = mutableListOf(Position.fromFen(startFen))
    private val playedMoves = mutableListOf<Move>()
    private val playedSans = mutableListOf<String>()

    val position: Position get() = positions.last()
    val moves: List<Move> get() = playedMoves
    val sans: List<String> get() = playedSans

    /** The position before the last move, or null at the start. */
    val previousPosition: Position? get() = positions.getOrNull(positions.size - 2)

    fun play(move: Move) {
        require(position.isLegal(move)) { "illegal move ${move.uci()} in ${position.fen()}" }
        playedSans += position.san(move)
        positions += position.play(move)
        playedMoves += move
    }

    fun undo(): Boolean {
        if (playedMoves.isEmpty()) return false
        positions.removeAt(positions.lastIndex)
        playedMoves.removeAt(playedMoves.lastIndex)
        playedSans.removeAt(playedSans.lastIndex)
        return true
    }

    fun status(): GameStatus {
        val pos = position
        if (pos.legalMoves().isEmpty()) return if (pos.inCheck()) GameStatus.CHECKMATE else GameStatus.STALEMATE
        if (insufficientMaterial(pos)) return GameStatus.INSUFFICIENT_MATERIAL
        if (pos.halfmoveClock >= 100) return GameStatus.FIFTY_MOVES
        val key = pos.repetitionKey()
        if (positions.count { it.repetitionKey() == key } >= 3) return GameStatus.REPETITION
        return GameStatus.ONGOING
    }

    /** Move list as "1. e4 e5 2. Nf3", numbered from the start position. */
    fun moveText(): String {
        val start = positions.first()
        val sb = StringBuilder()
        var number = start.fullmoveNumber
        var white = start.sideToMove == WHITE
        for ((i, san) in playedSans.withIndex()) {
            if (white) sb.append(number).append(". ")
            else if (i == 0) sb.append(number).append("... ")
            sb.append(san).append(' ')
            if (!white) number++
            white = !white
        }
        return sb.toString().trim()
    }

    fun pgn(whiteName: String, blackName: String, date: String): String {
        val result = when (status()) {
            GameStatus.CHECKMATE -> if (position.sideToMove == WHITE) "0-1" else "1-0"
            GameStatus.ONGOING -> "*"
            else -> "1/2-1/2"
        }
        val sb = StringBuilder()
        sb.append("[Event \"Over-the-board game\"]\n")
        sb.append("[Date \"").append(date).append("\"]\n")
        sb.append("[White \"").append(whiteName).append("\"]\n")
        sb.append("[Black \"").append(blackName).append("\"]\n")
        sb.append("[Result \"").append(result).append("\"]\n")
        if (startFen != START_FEN) {
            sb.append("[SetUp \"1\"]\n[FEN \"").append(startFen).append("\"]\n")
        }
        sb.append('\n').append(moveText())
        if (sb.last() != '\n') sb.append(' ')
        sb.append(result).append('\n')
        return sb.toString()
    }

    private fun insufficientMaterial(pos: Position): Boolean {
        val minors = mutableListOf<Int>()
        for (sq in 0 until 64) {
            when (abs(pos[sq])) {
                PAWN, ROOK, QUEEN -> return false
                KNIGHT, BISHOP -> minors += sq
            }
        }
        if (minors.size <= 1) return true
        // Only bishops, all on the same colour of square.
        return minors.all { abs(pos[it]) == BISHOP } &&
            minors.map { (fileOf(it) + rankOf(it)) % 2 }.distinct().size == 1
    }
}

package com.teamadvert.chesscam.chess

import kotlin.math.abs

private val PIECE_WORDS = arrayOf("", "Pawn", "Knight", "Bishop", "Rook", "Queen", "King")

fun pieceWord(kind: Int): String = PIECE_WORDS[kind]

fun colorWord(color: Int): String = if (color == WHITE) "White" else "Black"

/**
 * Words for text-to-speech, e.g. "Knight g1 to f3", "Bishop c4 takes f7, check",
 * "Castle kingside". The origin square is always said so the move is easy to find
 * on a physical board.
 */
fun spokenMove(position: Position, move: Move): String {
    val sb = StringBuilder()
    if (position.isCastle(move)) {
        sb.append(if (fileOf(move.to) == 6) "Castle kingside" else "Castle queenside")
    } else {
        sb.append(pieceWord(abs(position[move.from])))
            .append(' ').append(spokenSquare(move.from))
            .append(if (position.isCapture(move)) " takes " else " to ")
            .append(spokenSquare(move.to))
        if (move.promotion != EMPTY) sb.append(", promote to ").append(pieceWord(move.promotion).lowercase())
    }
    val next = position.play(move)
    if (next.inCheck()) sb.append(if (next.legalMoves().isEmpty()) ", checkmate" else ", check")
    return sb.toString()
}

// Upper-case file letters are read out as letters ("E 4") rather than as words.
private fun spokenSquare(sq: Int): String = "${'A' + fileOf(sq)}${rankOf(sq) + 1}"

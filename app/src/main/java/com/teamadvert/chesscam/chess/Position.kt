package com.teamadvert.chesscam.chess

import kotlin.math.abs
import kotlin.math.sign

// Pieces are signed ints: positive for White, negative for Black, 0 for empty.
const val EMPTY = 0
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

const val WHITE = 1
const val BLACK = -1

const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

// Squares are 0..63 with a1 = 0, b1 = 1, ..., h8 = 63.
fun square(file: Int, rank: Int) = rank * 8 + file
fun fileOf(sq: Int) = sq and 7
fun rankOf(sq: Int) = sq shr 3
fun squareName(sq: Int) = "${'a' + fileOf(sq)}${rankOf(sq) + 1}"

fun parseSquare(name: String): Int {
    require(name.length == 2 && name[0] in 'a'..'h' && name[1] in '1'..'8') { "bad square: $name" }
    return square(name[0] - 'a', name[1] - '1')
}

data class Move(val from: Int, val to: Int, val promotion: Int = EMPTY) {
    fun uci(): String = squareName(from) + squareName(to) +
        if (promotion != EMPTY) "nbrq"[promotion - KNIGHT].toString() else ""

    companion object {
        fun fromUci(text: String): Move? {
            if (text.length !in 4..5) return null
            return try {
                val promo = if (text.length == 5) "nbrq".indexOf(text[4]).let { if (it < 0) return null else it + KNIGHT } else EMPTY
                Move(parseSquare(text.substring(0, 2)), parseSquare(text.substring(2, 4)), promo)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

private const val CASTLE_WK = 1
private const val CASTLE_WQ = 2
private const val CASTLE_BK = 4
private const val CASTLE_BQ = 8

private val KNIGHT_STEPS = arrayOf(
    intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
    intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
)
private val KING_STEPS = arrayOf(
    intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
    intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
)
private val DIAGONALS = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
private val ORTHOGONALS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

private fun offset(sq: Int, df: Int, dr: Int): Int {
    val f = fileOf(sq) + df
    val r = rankOf(sq) + dr
    return if (f in 0..7 && r in 0..7) square(f, r) else -1
}

/** An immutable chess position with full legal move generation. */
class Position private constructor(
    private val board: IntArray,
    val sideToMove: Int,
    private val castling: Int,
    /** Square a pawn may capture onto en passant, or -1. Only set when a capture is possible. */
    val enPassant: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    operator fun get(sq: Int): Int = board[sq]

    fun kingSquare(color: Int): Int = board.indexOf(color * KING)

    fun inCheck(): Boolean = isAttacked(kingSquare(sideToMove), -sideToMove)

    fun isAttacked(sq: Int, by: Int): Boolean {
        if (sq < 0) return false
        for (df in intArrayOf(-1, 1)) {
            val s = offset(sq, df, -by)
            if (s >= 0 && board[s] == by * PAWN) return true
        }
        for (st in KNIGHT_STEPS) {
            val s = offset(sq, st[0], st[1])
            if (s >= 0 && board[s] == by * KNIGHT) return true
        }
        for (st in KING_STEPS) {
            val s = offset(sq, st[0], st[1])
            if (s >= 0 && board[s] == by * KING) return true
        }
        if (slides(sq, DIAGONALS, by * BISHOP, by * QUEEN)) return true
        return slides(sq, ORTHOGONALS, by * ROOK, by * QUEEN)
    }

    private fun slides(sq: Int, dirs: Array<IntArray>, a: Int, b: Int): Boolean {
        for (d in dirs) {
            var s = offset(sq, d[0], d[1])
            while (s >= 0) {
                val p = board[s]
                if (p != EMPTY) {
                    if (p == a || p == b) return true
                    break
                }
                s = offset(s, d[0], d[1])
            }
        }
        return false
    }

    private val legal: List<Move> by lazy {
        pseudoLegalMoves().filter { m ->
            val next = play(m)
            !next.isAttacked(next.kingSquare(sideToMove), -sideToMove)
        }
    }

    fun legalMoves(): List<Move> = legal

    fun isLegal(move: Move): Boolean = move in legal

    private fun pseudoLegalMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        val us = sideToMove
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == EMPTY || p.sign != us) continue
            when (abs(p)) {
                PAWN -> pawnMoves(sq, out)
                KNIGHT -> steps(sq, KNIGHT_STEPS, out)
                BISHOP -> rays(sq, DIAGONALS, out)
                ROOK -> rays(sq, ORTHOGONALS, out)
                QUEEN -> { rays(sq, DIAGONALS, out); rays(sq, ORTHOGONALS, out) }
                KING -> { steps(sq, KING_STEPS, out); castles(sq, out) }
            }
        }
        return out
    }

    private fun pawnMoves(sq: Int, out: MutableList<Move>) {
        val us = sideToMove
        val one = offset(sq, 0, us)
        if (one >= 0 && board[one] == EMPTY) {
            addPawn(sq, one, out)
            val startRank = if (us == WHITE) 1 else 6
            val two = offset(sq, 0, 2 * us)
            if (rankOf(sq) == startRank && board[two] == EMPTY) out += Move(sq, two)
        }
        for (df in intArrayOf(-1, 1)) {
            val t = offset(sq, df, us)
            if (t < 0) continue
            if (board[t] * us < 0 || t == enPassant) addPawn(sq, t, out)
        }
    }

    private fun addPawn(from: Int, to: Int, out: MutableList<Move>) {
        if (rankOf(to) == 0 || rankOf(to) == 7) {
            for (promo in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) out += Move(from, to, promo)
        } else {
            out += Move(from, to)
        }
    }

    private fun steps(sq: Int, deltas: Array<IntArray>, out: MutableList<Move>) {
        for (d in deltas) {
            val t = offset(sq, d[0], d[1])
            if (t >= 0 && board[t] * sideToMove <= 0) out += Move(sq, t)
        }
    }

    private fun rays(sq: Int, dirs: Array<IntArray>, out: MutableList<Move>) {
        for (d in dirs) {
            var t = offset(sq, d[0], d[1])
            while (t >= 0) {
                val p = board[t]
                if (p * sideToMove > 0) break
                out += Move(sq, t)
                if (p != EMPTY) break
                t = offset(t, d[0], d[1])
            }
        }
    }

    private fun castles(sq: Int, out: MutableList<Move>) {
        val us = sideToMove
        val home = if (us == WHITE) 4 else 60
        if (sq != home || isAttacked(home, -us)) return
        val (kingSide, queenSide) = if (us == WHITE) CASTLE_WK to CASTLE_WQ else CASTLE_BK to CASTLE_BQ
        if (castling and kingSide != 0 && board[home + 3] == us * ROOK &&
            board[home + 1] == EMPTY && board[home + 2] == EMPTY &&
            !isAttacked(home + 1, -us) && !isAttacked(home + 2, -us)
        ) out += Move(home, home + 2)
        if (castling and queenSide != 0 && board[home - 4] == us * ROOK &&
            board[home - 1] == EMPTY && board[home - 2] == EMPTY && board[home - 3] == EMPTY &&
            !isAttacked(home - 1, -us) && !isAttacked(home - 2, -us)
        ) out += Move(home, home - 2)
    }

    fun isCastle(move: Move): Boolean = abs(board[move.from]) == KING && abs(fileOf(move.to) - fileOf(move.from)) == 2

    fun isCapture(move: Move): Boolean =
        board[move.to] != EMPTY || (abs(board[move.from]) == PAWN && fileOf(move.from) != fileOf(move.to))

    /** Returns the position after [move]. The move must be at least pseudo-legal. */
    fun play(move: Move): Position {
        val b = board.copyOf()
        val us = sideToMove
        val piece = b[move.from]
        val kind = abs(piece)
        val captured = b[move.to]
        if (kind == PAWN && move.to == enPassant && captured == EMPTY) {
            b[square(fileOf(move.to), rankOf(move.from))] = EMPTY
        }
        if (kind == KING && abs(fileOf(move.to) - fileOf(move.from)) == 2) {
            val r = rankOf(move.from)
            if (fileOf(move.to) == 6) {
                b[square(5, r)] = b[square(7, r)]; b[square(7, r)] = EMPTY
            } else {
                b[square(3, r)] = b[square(0, r)]; b[square(0, r)] = EMPTY
            }
        }
        b[move.to] = if (move.promotion != EMPTY) us * move.promotion else piece
        b[move.from] = EMPTY

        var rights = castling
        for (s in intArrayOf(move.from, move.to)) {
            rights = rights and when (s) {
                0 -> CASTLE_WQ.inv(); 7 -> CASTLE_WK.inv(); 4 -> (CASTLE_WK or CASTLE_WQ).inv()
                56 -> CASTLE_BQ.inv(); 63 -> CASTLE_BK.inv(); 60 -> (CASTLE_BK or CASTLE_BQ).inv()
                else -> -1
            }
        }

        var ep = -1
        if (kind == PAWN && abs(rankOf(move.to) - rankOf(move.from)) == 2) {
            val target = square(fileOf(move.from), rankOf(move.from) + us)
            val enemyPawnBeside = intArrayOf(-1, 1).any { df ->
                val s = offset(move.to, df, 0)
                s >= 0 && b[s] == -us * PAWN
            }
            if (enemyPawnBeside) ep = target
        }
        val reset = kind == PAWN || captured != EMPTY
        return Position(
            b, -us, rights, ep,
            if (reset) 0 else halfmoveClock + 1,
            if (us == BLACK) fullmoveNumber + 1 else fullmoveNumber,
        )
    }

    /** Standard algebraic notation, e.g. "Nf3", "exd5", "O-O", "e8=Q+". */
    fun san(move: Move): String {
        val kind = abs(board[move.from])
        val sb = StringBuilder()
        if (isCastle(move)) {
            sb.append(if (fileOf(move.to) == 6) "O-O" else "O-O-O")
        } else {
            val capture = isCapture(move)
            if (kind == PAWN) {
                if (capture) sb.append('a' + fileOf(move.from))
            } else {
                sb.append("NBRQK"[kind - KNIGHT])
                val rivals = legalMoves().filter {
                    it.to == move.to && it.from != move.from && abs(board[it.from]) == kind
                }
                if (rivals.isNotEmpty()) {
                    when {
                        rivals.none { fileOf(it.from) == fileOf(move.from) } -> sb.append('a' + fileOf(move.from))
                        rivals.none { rankOf(it.from) == rankOf(move.from) } -> sb.append('1' + rankOf(move.from))
                        else -> sb.append(squareName(move.from))
                    }
                }
            }
            if (capture) sb.append('x')
            sb.append(squareName(move.to))
            if (move.promotion != EMPTY) sb.append('=').append("NBRQ"[move.promotion - KNIGHT])
        }
        val next = play(move)
        if (next.inCheck()) sb.append(if (next.legalMoves().isEmpty()) '#' else '+')
        return sb.toString()
    }

    fun fen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var gap = 0
            for (f in 0..7) {
                val p = board[square(f, r)]
                if (p == EMPTY) { gap++; continue }
                if (gap > 0) { sb.append(gap); gap = 0 }
                val c = " pnbrqk"[abs(p)]
                sb.append(if (p > 0) c.uppercaseChar() else c)
            }
            if (gap > 0) sb.append(gap)
            if (r > 0) sb.append('/')
        }
        sb.append(if (sideToMove == WHITE) " w " else " b ")
        val rights = buildString {
            if (castling and CASTLE_WK != 0) append('K')
            if (castling and CASTLE_WQ != 0) append('Q')
            if (castling and CASTLE_BK != 0) append('k')
            if (castling and CASTLE_BQ != 0) append('q')
        }
        sb.append(rights.ifEmpty { "-" })
        sb.append(' ').append(if (enPassant >= 0) squareName(enPassant) else "-")
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    /** Identifies the position for repetition checks (placement, side, castling, en passant). */
    fun repetitionKey(): String = fen().split(' ').take(4).joinToString(" ")

    companion object {
        fun start(): Position = fromFen(START_FEN)

        /** Parses a FEN string. Throws IllegalArgumentException if it is malformed or unplayable. */
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size >= 2) { "FEN needs at least piece placement and side to move" }
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN needs 8 ranks" }
            val b = IntArray(64)
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (c in row) {
                    if (c.isDigit()) {
                        f += c - '0'
                    } else {
                        val kind = " pnbrqk".indexOf(c.lowercaseChar())
                        require(kind > 0 && f < 8) { "bad FEN rank: $row" }
                        b[square(f, r)] = if (c.isUpperCase()) kind else -kind
                        f++
                    }
                }
                require(f == 8) { "bad FEN rank: $row" }
            }
            require(b.count { it == KING } == 1 && b.count { it == -KING } == 1) { "each side needs exactly one king" }
            val side = when (parts[1]) {
                "w" -> WHITE
                "b" -> BLACK
                else -> throw IllegalArgumentException("side to move must be w or b")
            }
            var rights = 0
            parts.getOrNull(2)?.forEach {
                rights = rights or when (it) {
                    'K' -> CASTLE_WK; 'Q' -> CASTLE_WQ; 'k' -> CASTLE_BK; 'q' -> CASTLE_BQ; else -> 0
                }
            }
            // Drop castling rights the placement cannot support.
            if (b[4] != KING) rights = rights and (CASTLE_WK or CASTLE_WQ).inv()
            if (b[7] != ROOK) rights = rights and CASTLE_WK.inv()
            if (b[0] != ROOK) rights = rights and CASTLE_WQ.inv()
            if (b[60] != -KING) rights = rights and (CASTLE_BK or CASTLE_BQ).inv()
            if (b[63] != -ROOK) rights = rights and CASTLE_BK.inv()
            if (b[56] != -ROOK) rights = rights and CASTLE_BQ.inv()

            var ep = parts.getOrNull(3)?.takeIf { it != "-" }?.let { parseSquare(it) } ?: -1
            if (ep >= 0) {
                val pawnRank = if (side == WHITE) 4 else 3
                val capturable = rankOf(ep) == (if (side == WHITE) 5 else 2) && intArrayOf(-1, 1).any { df ->
                    val s = offset(square(fileOf(ep), pawnRank), df, 0)
                    s >= 0 && b[s] == side * PAWN
                }
                if (!capturable) ep = -1
            }
            val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull() ?: 1
            val pos = Position(b, side, rights, ep, half, full)
            require(!pos.isAttacked(pos.kingSquare(-side), side)) { "the side not to move is in check" }
            return pos
        }
    }
}

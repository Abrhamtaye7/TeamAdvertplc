package com.teamadvert.chesscam.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionTest {

    private fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1
        val moves = pos.legalMoves()
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(pos.play(it), depth - 1) }
    }

    private fun play(pos: Position, vararg uci: String): Position =
        uci.fold(pos) { p, u -> p.play(Move.fromUci(u)!!.also { assertTrue("$u legal in ${p.fen()}", p.isLegal(it)) }) }

    @Test fun perftStart() {
        val p = Position.start()
        assertEquals(listOf(20L, 400L, 8902L, 197281L), (1..4).map { perft(p, it) })
    }

    @Test fun perftKiwipete() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(listOf(48L, 2039L, 97862L), (1..3).map { perft(p, it) })
    }

    @Test fun perftEnPassantAndPins() {
        val p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(listOf(14L, 191L, 2812L, 43238L), (1..4).map { perft(p, it) })
    }

    @Test fun perftPromotionsAndChecks() {
        val p = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(listOf(6L, 264L, 9467L), (1..3).map { perft(p, it) })
    }

    @Test fun perftPosition5() {
        val p = Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(listOf(44L, 1486L, 62379L), (1..3).map { perft(p, it) })
    }

    @Test fun fenRoundTrip() {
        val fens = listOf(
            START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
        )
        for (f in fens) assertEquals(f, Position.fromFen(f).fen())
    }

    @Test fun enPassantSquareOnlyWhenCapturable() {
        assertEquals("-", play(Position.start(), "e2e4").fen().split(' ')[3])
        val p = play(Position.start(), "e2e4", "a7a6", "e4e5", "d7d5")
        assertEquals("d6", p.fen().split(' ')[3])
        val after = play(p, "e5d6")
        assertEquals(EMPTY, after[parseSquare("d5")])
        assertEquals(PAWN, after[parseSquare("d6")])
    }

    @Test fun castlingMovesRookAndDropsRights() {
        val p = play(Position.start(), "e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1g1")
        assertEquals(KING, p[parseSquare("g1")])
        assertEquals(ROOK, p[parseSquare("f1")])
        assertEquals(EMPTY, p[parseSquare("h1")])
        assertEquals("kq", p.fen().split(' ')[2])
    }

    @Test fun cannotCastleThroughCheck() {
        val p = Position.fromFen("4k3/8/8/8/8/8/5r2/4K2R w K - 0 1")
        assertFalse(p.isLegal(Move(parseSquare("e1"), parseSquare("g1"))))
    }

    @Test fun san() {
        val p = Position.start()
        assertEquals("e4", p.san(Move.fromUci("e2e4")!!))
        assertEquals("Nf3", p.san(Move.fromUci("g1f3")!!))
        val q = Position.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        assertEquals("O-O", q.san(Move.fromUci("e1g1")!!))
        assertEquals("O-O-O", q.san(Move.fromUci("e1c1")!!))
        assertEquals("Ra8+", q.san(Move.fromUci("a1a8")!!))
        // Two knights can reach d2: needs the file.
        val n = Position.fromFen("4k3/8/8/8/8/5N2/8/1N2K3 w - - 0 1")
        assertEquals("Nbd2", n.san(Move.fromUci("b1d2")!!))
        val promo = Position.fromFen("k7/4P3/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals("e8=Q+", promo.san(Move.fromUci("e7e8q")!!))
        val mate = play(Position.start(), "f2f3", "e7e5", "g2g4")
        assertEquals("Qh4#", mate.san(Move.fromUci("d8h4")!!))
    }

    @Test fun uciParsing() {
        assertEquals(Move(parseSquare("e7"), parseSquare("e8"), QUEEN), Move.fromUci("e7e8q"))
        assertEquals("e7e8q", Move.fromUci("e7e8q")!!.uci())
        assertNull(Move.fromUci("e9e8"))
        assertNull(Move.fromUci("(none)"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingKing() {
        Position.fromFen("8/8/8/8/8/8/8/4K3 w - - 0 1")
    }
}

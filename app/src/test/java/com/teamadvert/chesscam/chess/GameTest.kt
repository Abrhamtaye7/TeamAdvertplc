package com.teamadvert.chesscam.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {

    private fun Game.play(vararg uci: String) = uci.forEach { play(Move.fromUci(it)!!) }

    @Test fun foolsMate() {
        val g = Game()
        g.play("f2f3", "e7e5", "g2g4", "d8h4")
        assertEquals(GameStatus.CHECKMATE, g.status())
        assertEquals("1. f3 e5 2. g4 Qh4#", g.moveText())
        assertTrue(g.pgn("A", "B", "2026.01.01").contains("1. f3 e5 2. g4 Qh4# 0-1"))
    }

    @Test fun stalemate() {
        val g = Game("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(GameStatus.STALEMATE, g.status())
    }

    @Test fun threefoldRepetition() {
        val g = Game()
        g.play("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1")
        assertEquals(GameStatus.ONGOING, g.status())
        g.play("f6g8")
        assertEquals(GameStatus.REPETITION, g.status())
    }

    @Test fun insufficientMaterial() {
        assertEquals(GameStatus.INSUFFICIENT_MATERIAL, Game("4k3/8/8/8/8/8/8/4KB2 w - - 0 1").status())
        assertEquals(GameStatus.ONGOING, Game("4k3/8/8/8/8/8/8/4KBN1 w - - 0 1").status())
    }

    @Test fun undo() {
        val g = Game()
        g.play("e2e4", "e7e5")
        assertTrue(g.undo())
        assertEquals("1. e4", g.moveText())
        assertEquals(BLACK, g.position.sideToMove)
    }

    @Test fun moveTextFromBlackToMove() {
        val g = Game("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
        g.play("e7e5", "g1f3")
        assertEquals("1... e5 2. Nf3", g.moveText())
    }

    @Test fun spokenMoves() {
        val p = Position.start()
        assertEquals("Knight G1 to F3", spokenMove(p, Move.fromUci("g1f3")!!))
        val castle = Position.fromFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
        assertEquals("Castle kingside", spokenMove(castle, Move.fromUci("e1g1")!!))
        val mate = Game().apply { play("f2f3", "e7e5", "g2g4") }.position
        assertEquals("Queen D8 to H4, checkmate", spokenMove(mate, Move.fromUci("d8h4")!!))
        val capture = Position.fromFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")
        assertEquals("Pawn E4 takes D5", spokenMove(capture, Move.fromUci("e4d5")!!))
    }
}

package com.teamadvert.chesscam.vision

import com.teamadvert.chesscam.chess.Game
import com.teamadvert.chesscam.chess.GameStatus
import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.Position
import com.teamadvert.chesscam.chess.parseSquare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MoveDetectorTest {
    private val board = SyntheticBoard()
    private val sampler = board.sampler

    private fun features(img: SyntheticBoard.Image) = board.features(img)
    private fun render(pos: Position, brightness: Int = 0, hand: Pair<Int, Int>? = null) = board.render(pos, brightness, hand)
    private fun settle(d: MoveDetector, shown: Position, tracked: Position, hint: Move? = null, brightness: Int = 0) =
        board.settle(d, shown, tracked, hint, brightness)
    private fun handOver(d: MoveDetector, pos: Position, tracked: Position) = board.handOver(d, pos, tracked)
    private fun detector() = board.detector()

    private fun synced(): Pair<MoveDetector, Game> {
        val d = detector()
        val g = Game()
        assertEquals(Detection.Synced, settle(d, g.position, g.position))
        return d to g
    }

    @Test fun followsAnOpening() {
        val (d, g) = synced()
        val line = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6", "d7c6", "e1g1", "f7f6", "d2d4", "e5d4")
        for (uci in line) {
            val move = Move.fromUci(uci)!!
            val after = g.position.play(move)
            handOver(d, after, g.position)
            val r = settle(d, after, g.position)
            assertEquals("after $uci", Detection.Found(listOf(move)), r)
            g.play(move)
            assertEquals(Detection.NoChange, d.process(features(render(g.position)), g.position, null))
        }
    }

    @Test fun ignoresSmallLightingChanges() {
        val (d, g) = synced()
        repeat(20) { d.process(features(render(g.position, brightness = it / 2)), g.position, null) }
        assertEquals(Detection.NoChange, settle(d, g.position, g.position, brightness = 10))
    }

    @Test fun waitsWhileAHandIsOverTheBoard() {
        val (d, g) = synced()
        val after = g.position.play(Move.fromUci("e2e4")!!)
        val r = d.process(features(render(after, hand = 300 to 400)), g.position, null)
        assertEquals(Detection.Moving, r)
    }

    @Test fun liftedPieceIsNotAMove() {
        val (d, g) = synced()
        // Knight lifted off g1 and not yet put down.
        val fen = g.position.fen().replace("RNBQKBNR", "RNBQKB1R")
        val r = settle(d, Position.fromFen(fen), g.position)
        assertTrue(r is Detection.Unclear && !r.lostTrack)
    }

    @Test fun detectsTwoQuickMoves() {
        val (d, g) = synced()
        val e4 = Move.fromUci("e2e4")!!
        val e5 = Move.fromUci("e7e5")!!
        val after = g.position.play(e4).play(e5)
        handOver(d, after, g.position)
        assertEquals(Detection.Found(listOf(e4, e5)), settle(d, after, g.position))
    }

    @Test fun detectsEnPassant() {
        val d = detector()
        val start = Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2")
        assertEquals(Detection.Synced, settle(d, start, start))
        val ep = Move.fromUci("e5d6")!!
        val after = start.play(ep)
        handOver(d, after, start)
        assertEquals(Detection.Found(listOf(ep)), settle(d, after, start))
    }

    @Test fun reportsLostTrackOnce() {
        val (d, g) = synced()
        // Two pieces vanish, which no legal move explains.
        val fen = g.position.fen().replace("RNBQKBNR", "R1BQKB1R")
        val shown = Position.fromFen(fen)
        var lost = 0
        repeat(120) {
            val r = d.process(features(render(shown)), g.position, null)
            if (r is Detection.Unclear && r.lostTrack) lost++
            assertTrue(r !is Detection.Found)
        }
        assertEquals(1, lost)
    }

    private fun randomLine(seed: Int, length: Int): List<Move> {
        val rnd = Random(seed)
        val g = Game()
        while (g.moves.size < length && g.status() == GameStatus.ONGOING) {
            val moves = g.position.legalMoves()
            g.play(moves[rnd.nextInt(moves.size)])
        }
        return g.moves.toList()
    }

    @Test fun randomGamesFromThreeCameraAngles() {
        val views = listOf(
            "white side" to SyntheticBoard.WHITE_SIDE,
            "black side" to SyntheticBoard.BLACK_SIDE,
            "overhead" to SyntheticBoard.OVERHEAD,
        )
        for ((name, corners) in views) {
            val missed = mutableListOf<String>()
            val wrong = mutableListOf<String>()
            var total = 0
            for (seed in 1..3) {
                val b = SyntheticBoard(corners, noise = 10, seed = seed)
                val d = b.detector()
                val g = Game()
                b.settle(d, g.position, g.position)
                val line = randomLine(seed, 40)
                val outcome = b.playThrough(d, g, line)
                missed += outcome.missed
                wrong += outcome.wrong
                total += line.size
            }
            assertTrue("$name: wrong moves $wrong", wrong.isEmpty())
            assertTrue("$name: missed ${missed.size} of $total: $missed", missed.size * 10 <= total)
        }
    }

    @Test fun behindPointsAwayFromTheCamera() {
        // The camera is on White's side, so each piece leans toward rank 8.
        assertEquals(parseSquare("e3"), sampler.geometry.behind[parseSquare("e2")])
        assertEquals(parseSquare("d8"), sampler.geometry.behind[parseSquare("d7")])
        assertEquals(-1, sampler.geometry.behind[parseSquare("a8")])
    }

    @Test fun cornersMapToTheRightPlaces() {
        val h = sampler.toImage!!
        // The a1 corner of the board maps to the first corner the user placed, h8 to the third.
        assertEquals(60.0, h.mapX(0.0, 8.0), 1e-6)
        assertEquals(560.0, h.mapY(0.0, 8.0), 1e-6)
        assertEquals(500.0, h.mapX(8.0, 0.0), 1e-6)
        assertEquals(120.0, h.mapY(8.0, 0.0), 1e-6)
    }
}

package com.teamadvert.chesscam.engine

import com.teamadvert.chesscam.chess.Move
import com.teamadvert.chesscam.chess.START_FEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UciEngineTest {

    @Test fun parsesInfoLines() {
        val i = UciInfo.parse("info depth 18 seldepth 25 multipv 1 score cp -34 nodes 1000 pv e7e5 g1f3")!!
        assertEquals(18, i.depth)
        assertEquals(-34, i.scoreCp)
        assertNull(i.mateIn)
        val m = UciInfo.parse("info depth 9 score mate 3 pv d8h4")!!
        assertEquals(3, m.mateIn)
        assertTrue(UciInfo.parse("info depth 5 score cp 10 lowerbound")!!.bound)
        assertNull(UciInfo.parse("info string NNUE evaluation enabled"))
        assertNull(UciInfo.parse("info depth 5 multipv 2 score cp 3"))
    }

    /** A tiny shell stand-in for Stockfish that always answers e2e4. */
    private fun fakeEngine(): File {
        val f = File.createTempFile("fake-uci", ".sh")
        f.writeText(
            """
            |#!/bin/sh
            |while read line; do
            |  case "${'$'}line" in
            |    uci) echo "id name Fake"; echo "uciok" ;;
            |    isready) echo "readyok" ;;
            |    go*) echo "info depth 12 score cp 31 pv e2e4 e7e5"; echo "bestmove e2e4 ponder e7e5" ;;
            |    quit) exit 0 ;;
            |  esac
            |done
            """.trimMargin(),
        )
        f.setExecutable(true)
        f.deleteOnExit()
        return f
    }

    @Test fun runsASearch() {
        val engine = UciEngine(listOf("sh", fakeEngine().absolutePath))
        val errors = mutableListOf<String>()
        engine.start(listOf("Threads" to "1")) { errors += it }
        val done = CountDownLatch(1)
        var result: EngineResult? = null
        engine.search(START_FEN, emptyList(), 100) {
            result = it
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(Move.fromUci("e2e4"), result!!.bestMove)
        assertEquals(31, result!!.scoreCp)
        assertEquals(12, result!!.depth)
        engine.close()
        assertTrue(errors.isEmpty())
    }

    @Test fun newerSearchReplacesOlder() {
        val engine = UciEngine(listOf("sh", fakeEngine().absolutePath))
        engine.start(emptyList()) {}
        val done = CountDownLatch(1)
        val delivered = mutableListOf<String>()
        engine.search(START_FEN, emptyList(), 100) { synchronized(delivered) { delivered += "first" } }
        engine.search(START_FEN, listOf(Move.fromUci("e2e4")!!), 100) {
            synchronized(delivered) { delivered += "second" }
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(listOf("second"), synchronized(delivered) { delivered.toList() })
        engine.close()
    }
}

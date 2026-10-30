package com.teamadvert.chesscam

import android.content.Context
import com.teamadvert.chesscam.chess.START_FEN
import com.teamadvert.chesscam.chess.WHITE

/** User preferences and the saved game, kept in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("chesscam", Context.MODE_PRIVATE)

    var myColor: Int
        get() = prefs.getInt("myColor", WHITE)
        set(v) = prefs.edit().putInt("myColor", v).apply()

    var thinkTimeMs: Int
        get() = prefs.getInt("thinkTimeMs", 1000)
        set(v) = prefs.edit().putInt("thinkTimeMs", v).apply()

    var voice: Boolean
        get() = prefs.getBoolean("voice", true)
        set(v) = prefs.edit().putBoolean("voice", v).apply()

    /** Speak moves while the floating overlay is on (off by default: the overlay shows colors). */
    var overlayVoice: Boolean
        get() = prefs.getBoolean("overlayVoice", false)
        set(v) = prefs.edit().putBoolean("overlayVoice", v).apply()

    /** How strongly the overlay's colors are drawn, 0.2..1. */
    var overlayOpacity: Float
        get() = prefs.getFloat("overlayOpacity", 0.6f)
        set(v) = prefs.edit().putFloat("overlayOpacity", v).apply()

    var announceOpponent: Boolean
        get() = prefs.getBoolean("announceOpponent", true)
        set(v) = prefs.edit().putBoolean("announceOpponent", v).apply()

    var showChanges: Boolean
        get() = prefs.getBoolean("showChanges", true)
        set(v) = prefs.edit().putBoolean("showChanges", v).apply()

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 1f)
        set(v) = prefs.edit().putFloat("sensitivity", v).apply()

    var seenHelp: Boolean
        get() = prefs.getBoolean("seenHelp", false)
        set(v) = prefs.edit().putBoolean("seenHelp", v).apply()

    var startFen: String
        get() = prefs.getString("startFen", START_FEN) ?: START_FEN
        set(v) = prefs.edit().putString("startFen", v).apply()

    /** Board corners a1, h1, h8, a8 as fractions of the frame width and height. */
    var corners: FloatArray?
        get() = prefs.getString("corners", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 8 }?.toFloatArray()
        set(v) = prefs.edit().putString("corners", v?.joinToString(",")).apply()

    /** The game in progress as start FEN plus UCI moves, so it survives the app being closed. */
    var savedGame: Pair<String, List<String>>?
        get() {
            val fen = prefs.getString("gameFen", null) ?: return null
            val moves = prefs.getString("gameMoves", "")!!.split(' ').filter { it.isNotEmpty() }
            return fen to moves
        }
        set(v) = prefs.edit()
            .putString("gameFen", v?.first)
            .putString("gameMoves", v?.second?.joinToString(" "))
            .apply()
}

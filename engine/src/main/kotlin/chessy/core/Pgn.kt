package chessy.core

/**
 * Portable Game Notation, the standard format to save and share chess games
 * (https://www.chessprogramming.org/Portable_Game_Notation).
 */
object Pgn {

    private const val MAX_LINE_LENGTH = 80

    private val SEVEN_TAG_ROSTER = listOf("Event", "Site", "Date", "Round", "White", "Black", "Result")

    /** PGN of a game: tags, then moves in SAN and the result. */
    fun write(game: Game): String {
        // Seven Tag Roster first, in its order
        val headers = linkedMapOf<String, String>()
        for (name in SEVEN_TAG_ROSTER) {
            headers[name] = game.headers[name] ?: "?"
        }
        headers["Result"] = game.result
        headers.putAll(game.headers.filterKeys { it !in SEVEN_TAG_ROSTER })
        if (game.startingFen != Position.STARTING_FEN) {
            headers["SetUp"] = "1"
            headers["FEN"] = game.startingFen
        }

        val tokens = mutableListOf<String>()
        val start = Position.fromFen(game.startingFen)
        var moveNumber = start.fullmoveNumber
        var whiteToMove = start.sideToMove == WHITE
        for ((index, san) in game.sanMoves().withIndex()) {
            if (whiteToMove) {
                tokens.add("$moveNumber.")
            } else if (index == 0) {
                tokens.add("$moveNumber...")
            }
            tokens.add(san)
            if (!whiteToMove) moveNumber++
            whiteToMove = !whiteToMove
        }
        tokens.add(game.result)

        val tags = headers.entries.joinToString("\n") { (name, value) -> "[$name \"${escape(value)}\"]" }
        return "$tags\n\n${wrap(tokens)}\n"
    }

    /** Reads the first game of a PGN text. */
    fun read(pgn: String): Game = readAll(pgn).firstOrNull() ?: throw IllegalArgumentException("No game found")

    /**
     * Reads all the games of a PGN text. Comments, variations and numeric annotation glyphs are ignored.
     *
     * @throws IllegalArgumentException if a game has an invalid or illegal move.
     */
    fun readAll(pgn: String): List<Game> = Reader(pgn).readGames()

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Lines of the movetext have at most 80 characters. */
    private fun wrap(tokens: List<String>): String {
        val lines = mutableListOf(StringBuilder())
        for (token in tokens) {
            val line = lines.last()
            if (line.isNotEmpty() && line.length + 1 + token.length > MAX_LINE_LENGTH) {
                lines.add(StringBuilder(token))
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(token)
            }
        }
        return lines.joinToString("\n")
    }

    private class Reader(private val text: String) {

        private var index = 0

        private val moveNumber = Regex("^\\d+\\.+")

        fun readGames(): List<Game> {
            val games = mutableListOf<Game>()
            while (true) {
                skipWhitespace()
                if (index >= text.length) break
                games.add(readGame())
            }
            return games
        }

        private fun readGame(): Game {
            val headers = linkedMapOf<String, String>()
            while (true) {
                skipWhitespace()
                if (peek() != '[') break
                val (name, value) = readTag()
                headers[name] = value
            }

            val game = Game(headers["FEN"] ?: Position.STARTING_FEN)
            for ((name, value) in headers) {
                if (name !in setOf("Result", "SetUp", "FEN")) game.headers[name] = value
            }

            while (true) {
                skipWhitespace()
                val char = peek() ?: break
                when (char) {
                    '{' -> skipUntil('}')
                    ';' -> skipUntil('\n')
                    '(' -> skipVariation()
                    '$' -> readToken() // Numeric annotation glyph
                    '[' -> break // Next game, this one has no result
                    else -> {
                        val token = readToken().replace(moveNumber, "")
                        when {
                            token.isEmpty() -> {}
                            token in Game.RESULTS -> {
                                if (!game.status.isOver) game.declaredResult = token
                                return game
                            }
                            else -> try {
                                game.play(token)
                            } catch (e: IllegalArgumentException) {
                                throw IllegalArgumentException("${e.message} after ${game.sanMoves()} in game ${headers}", e)
                            }
                        }
                    }
                }
            }
            headers["Result"]?.let { if (it in Game.RESULTS && !game.status.isOver) game.declaredResult = it }
            return game
        }

        private fun readTag(): Pair<String, String> {
            index++ // [
            val name = StringBuilder()
            while (peek()?.isWhitespace() == false) name.append(text[index++])
            skipWhitespace()
            require(peek() == '"') { "Invalid tag $name" }
            index++
            val value = StringBuilder()
            while (peek() != null && peek() != '"') {
                if (peek() == '\\') index++
                value.append(text[index++])
            }
            skipUntil(']')
            return name.toString() to value.toString()
        }

        /** Reads until whitespace or the start of a comment, variation or tag. */
        private fun readToken(): String {
            val start = index
            while (peek()?.let { !it.isWhitespace() && it !in "{}();[" } == true) index++
            return text.substring(start, index)
        }

        private fun skipVariation() {
            var depth = 0
            do {
                when (peek()) {
                    '(' -> depth++
                    ')' -> depth--
                    '{' -> { skipUntil('}'); continue }
                    null -> return
                }
                index++
            } while (depth > 0)
        }

        /** Skips until after [end]. */
        private fun skipUntil(end: Char) {
            while (index < text.length && text[index] != end) index++
            index++
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) index++
        }

        private fun peek(): Char? = text.getOrNull(index)
    }
}

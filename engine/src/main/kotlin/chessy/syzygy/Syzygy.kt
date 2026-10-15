package chessy.syzygy

import chessy.core.BLACK
import chessy.core.KING
import chessy.core.PAWN
import chessy.core.Position
import chessy.core.WHITE
import chessy.core.colorOf
import chessy.core.fileOf
import chessy.core.forEachSquare
import chessy.core.popCount
import chessy.core.rankOf
import chessy.core.typeOf
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Syzygy endgame tablebases (https://chessprogramming.org/Syzygy_Bases), by Ronald de Man: the result of every position
 * with few pieces, without castling rights. WDL tables (`.rtbw`) store win, draw or loss (with the fifty-move rule:
 * cursed wins and blessed losses), and DTZ tables (`.rtbz`) the distance to the next zeroing move (capture or pawn move).
 *
 * This is an implementation of the table format and its probing algorithm (originally written by Ronald de Man,
 * redistributable without restrictions), checked against python-chess with `tools/syzygy_check.py`.
 *
 * Tables are memory mapped when first used, so they cost no memory until then. It is thread-safe.
 */
class Syzygy(paths: String) {

    /** Tables by material, such as "KQvKR" (white pieces, then black), for WDL and DTZ. */
    private val wdlFiles = mutableMapOf<String, File>()
    private val dtzFiles = mutableMapOf<String, File>()
    private val tables = java.util.concurrent.ConcurrentHashMap<String, Table>()

    /** Most pieces (kings included) of the available tables, 0 without tables. */
    val maxPieces: Int

    init {
        for (path in paths.split(File.pathSeparatorChar, ';').filter { it.isNotBlank() }) {
            File(path.trim()).listFiles()?.forEach { file ->
                when (file.extension) {
                    "rtbw" -> wdlFiles[file.nameWithoutExtension] = file
                    "rtbz" -> dtzFiles[file.nameWithoutExtension] = file
                }
            }
        }
        maxPieces = wdlFiles.keys.maxOfOrNull { it.length - 1 } ?: 0
    }

    val isEmpty: Boolean get() = wdlFiles.isEmpty()

    /** Number of WDL tables. */
    val size: Int get() = wdlFiles.size

    /** Whether the [position] can be probed: few enough pieces and no castling rights. */
    fun covers(position: Position): Boolean =
        maxPieces > 0 && popCount(position.occupied) <= maxPieces && position.castlingRights == 0

    // ---- WDL ----

    /**
     * Win (2), cursed win (1, a win that the fifty-move rule turns into a draw), draw (0), blessed loss (-1) or loss (-2)
     * for the side to move, or null if the position is not in the tables. It also searches the captures,
     * because the tables do not store reliable values for positions with a winning capture.
     */
    fun probeWdl(position: Position): Int? {
        if (!covers(position)) return null
        return try {
            search(position, checkZeroingMoves = false).takeIf { it.state != State.FAIL }?.wdl
        } catch (e: java.io.IOException) {
            null
        }
    }

    // ---- DTZ ----

    /**
     * Distance to zero: plies until the next capture or pawn move with best play, positive if the side to move wins
     * and negative if it loses (with 100 more for cursed wins and blessed losses), 0 for draws, or null if the position
     * is not in the tables. A mate in 1 is 1.
     */
    fun probeDtz(position: Position): Int? {
        if (!covers(position)) return null
        return try {
            dtz(position)
        } catch (e: java.io.IOException) {
            null
        }
    }

    private enum class State { OK, FAIL, ZEROING_BEST_MOVE, CHANGE_STM }

    private class Result(val wdl: Int, val state: State)

    /**
     * WDL of the position searching the captures (and pawn moves with [checkZeroingMoves], for DTZ): the tables
     * may store a "don't care" value when the best move is a winning capture, and wrong values with en passant.
     */
    private fun search(position: Position, checkZeroingMoves: Boolean): Result {
        var best = LOSS
        val moves = chessy.core.MoveGenerator.legalMoves(position).toList()
        var searched = 0
        for (move in moves) {
            val pawn = typeOf(position.pieceOn(move.from)) == PAWN
            if (!move.isCapture && !(checkZeroingMoves && pawn)) continue
            searched++
            position.makeMove(move)
            val child = search(position, checkZeroingMoves = false)
            position.unmakeMove(move)
            if (child.state == State.FAIL) return Result(DRAW, State.FAIL)
            val value = -child.wdl
            if (value > best) {
                best = value
                if (value >= WIN) return Result(value, State.ZEROING_BEST_MOVE) // Winning zeroing move
            }
        }
        // When every legal move was searched, the stored value may be wrong (en passant, only captures...)
        val noMoreMoves = searched > 0 && searched == moves.size
        val value = if (noMoreMoves) best else {
            val probed = probeTable(position, Kind.WDL) ?: return Result(DRAW, State.FAIL)
            probed.first
        }
        if (best >= value) return Result(best, if (best > DRAW || noMoreMoves) State.ZEROING_BEST_MOVE else State.OK)
        return Result(value, State.OK)
    }

    private fun dtz(position: Position): Int? {
        val result = search(position, checkZeroingMoves = true)
        if (result.state == State.FAIL) return null
        val wdl = result.wdl
        if (wdl == DRAW) return 0 // DTZ tables do not store draws
        if (result.state == State.ZEROING_BEST_MOVE) return dtzBeforeZeroing(wdl)

        val probed = probeTable(position, Kind.DTZ, wdl) ?: return null
        if (probed.second != State.CHANGE_STM) {
            val dtz = probed.first
            return (dtz + if (wdl == BLESSED_LOSS || wdl == CURSED_WIN) 100 else 0) * Integer.signum(wdl)
        }

        // The table stores the other side to move: search one ply for the move that minimizes the DTZ
        var minDtz = Int.MAX_VALUE
        for (move in chessy.core.MoveGenerator.legalMoves(position).toList()) {
            val zeroing = move.isCapture || typeOf(position.pieceOn(move.from)) == PAWN
            position.makeMove(move)
            var dtz = if (zeroing) {
                val child = search(position, checkZeroingMoves = false)
                if (child.state == State.FAIL) { position.unmakeMove(move); return null }
                -dtzBeforeZeroing(child.wdl)
            } else {
                -(dtz(position) ?: run { position.unmakeMove(move); return null })
            }
            // A mating move has DTZ 1
            if (dtz == 1 && position.inCheck() && chessy.core.MoveGenerator.legalMoves(position).isEmpty()) minDtz = 1
            if (!zeroing) dtz += Integer.signum(dtz)
            if (dtz < minDtz && Integer.signum(dtz) == Integer.signum(wdl)) minDtz = dtz
            position.unmakeMove(move)
        }
        return if (minDtz == Int.MAX_VALUE) -1 else minDtz // Without legal moves it is mate
    }

    /** DTZ of a position whose best move zeroes the counter, from its WDL. */
    fun dtzBeforeZeroing(wdl: Int): Int = when (wdl) {
        WIN -> 1
        CURSED_WIN -> 101
        BLESSED_LOSS -> -101
        LOSS -> -1
        else -> 0
    }

    // ---- Tables ----

    private enum class Kind(val extension: String, val magic: Int) {
        WDL("rtbw", 0x5d23e871),
        DTZ("rtbz", 0xa50c66d7.toInt()),
    }

    /** Material of a [color] with the letters of the table names, strongest first: "KQRBNP". */
    private fun material(position: Position, color: Int): String = buildString {
        for (type in intArrayOf(KING, chessy.core.QUEEN, chessy.core.ROOK, chessy.core.BISHOP, chessy.core.KNIGHT, PAWN)) {
            repeat(popCount(position.pieces(color, type))) { append("PNBRQK"[type]) }
        }
    }

    /**
     * Probes the table of the [position] without searching: the WDL value, or the DTZ value (with the [wdl] of the
     * position), and the state (CHANGE_STM when the DTZ table only stores the other side to move). Null if it fails.
     */
    private fun probeTable(position: Position, kind: Kind, wdl: Int = DRAW): Pair<Int, State>? {
        if (popCount(position.occupied) == 2) return DRAW to State.OK // Only kings
        val white = material(position, WHITE)
        val black = material(position, BLACK)
        // Tables are named with the stronger side first: KQvKR, not KRvKQ
        val blackStronger: Boolean
        val name = when {
            "${white}v$black" in wdlFiles -> { blackStronger = false; "${white}v$black" }
            "${black}v$white" in wdlFiles -> { blackStronger = true; "${black}v$white" }
            else -> return null
        }
        val table = table(name, kind) ?: return null
        return table.probe(position, blackStronger, wdl)
    }

    private fun table(name: String, kind: Kind): Table? {
        val files = if (kind == Kind.WDL) wdlFiles else dtzFiles
        val file = files[name] ?: return null
        return tables.getOrPut("$name.${kind.extension}") { Table(name, file, kind) }
    }

    /**
     * Huffman-compressed data of a table for one side to move and, with pawns, one file of the leading pawn
     * (pairs data): the pieces in the order of the encoding, their groups and the decompression tables.
     */
    private class PairsData {
        var flags = 0
        val pieces = IntArray(MAX_PIECES)
        val groupLen = IntArray(MAX_PIECES + 1)
        val groupIdx = LongArray(MAX_PIECES + 1)
        var sizeofBlock = 0L
        var span = 0L
        var sparseIndexSize = 0L
        var blocksNum = 0L
        var blockLengthSize = 0L
        var maxSymLen = 0
        var minSymLen = 0
        var lowestSym = 0
        var base64 = LongArray(0)
        var symlen = IntArray(0)
        var btree = 0
        var sparseIndex = 0
        var blockLength = 0
        var data = 0
        /** DTZ: offsets of the value maps for each WDL result. */
        val mapIdx = IntArray(4)
    }

    /** A table file, memory mapped and parsed on its first probe. */
    private class Table(name: String, private val file: File, private val kind: Kind) {
        val hasPawns = 'P' in name
        val pieceCount = name.length - 1
        /** Same material for both colors: only white to move is stored. */
        val symmetric = name.substringBefore('v') == name.substringAfter('v')
        val hasUniquePieces: Boolean
        /** Pawns of the leading color (the one with fewer pawns, if both have) and of the other one. */
        val pawnCount = IntArray(2)

        init {
            val (white, black) = name.split('v')
            hasUniquePieces = listOf(white, black).any { side -> "QRBNP".any { piece -> side.count { it == piece } == 1 } }
            val whitePawns = white.count { it == 'P' }
            val blackPawns = black.count { it == 'P' }
            val leadingWhite = blackPawns == 0 || (whitePawns > 0 && blackPawns >= whitePawns)
            pawnCount[0] = if (leadingWhite) whitePawns else blackPawns
            pawnCount[1] = if (leadingWhite) blackPawns else whitePawns
        }

        private val sides = if (kind == Kind.WDL && !symmetric) 2 else 1
        private val files = if (hasPawns) 4 else 1
        private lateinit var buffer: ByteBuffer
        private val pairs = Array(2) { Array(4) { PairsData() } }
        /** DTZ: start of the value maps. */
        private var map = 0
        @Volatile
        private var ready = false

        fun pairs(side: Int, file: Int): PairsData = pairs[side % sides][if (hasPawns) file else 0]

        private fun open() {
            if (ready) return
            synchronized(this) {
                if (ready) return
                RandomAccessFile(file, "r").use { access ->
                    buffer = access.channel.map(FileChannel.MapMode.READ_ONLY, 0, access.length()).order(ByteOrder.LITTLE_ENDIAN)
                }
                parse()
                ready = true
            }
        }

        private fun u8(offset: Int): Int = buffer.get(offset).toInt() and 0xFF
        private fun u16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
        private fun u32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
        private fun u32BigEndian(offset: Int): Long = Integer.reverseBytes(buffer.getInt(offset)).toLong() and 0xFFFFFFFFL
        private fun u64BigEndian(offset: Int): Long = java.lang.Long.reverseBytes(buffer.getLong(offset))

        private fun parse() {
            require(buffer.getInt(0) == kind.magic) { "Invalid Syzygy table: $file" }
            var data = 4
            val split = u8(data) and 1 != 0
            val pawnsFlag = u8(data) and 2 != 0
            require(pawnsFlag == hasPawns && (split == (kind == Kind.WDL && !symmetric) || kind == Kind.DTZ)) { "Unexpected Syzygy table: $file" }
            data++
            val bothPawns = hasPawns && pawnCount[1] > 0
            for (f in 0 until files) {
                val order = arrayOf(
                    intArrayOf(u8(data) and 0xF, if (bothPawns) u8(data + 1) and 0xF else 0xF),
                    intArrayOf(u8(data) ushr 4, if (bothPawns) u8(data + 1) ushr 4 else 0xF),
                )
                data += 1 + if (bothPawns) 1 else 0
                for (k in 0 until pieceCount) {
                    for (i in 0 until sides) pairs(i, f).pieces[k] = if (i == 0) u8(data) and 0xF else u8(data) ushr 4
                    data++
                }
                for (i in 0 until sides) setGroups(pairs(i, f), order[i], f)
            }
            data += data and 1 // Word alignment
            for (f in 0 until files) for (i in 0 until sides) data = setSizes(pairs(i, f), data)
            if (kind == Kind.DTZ) data = setDtzMap(data)
            for (f in 0 until files) for (i in 0 until sides) {
                val d = pairs(i, f)
                d.sparseIndex = data
                data += (d.sparseIndexSize * SPARSE_ENTRY_SIZE).toInt()
            }
            for (f in 0 until files) for (i in 0 until sides) {
                val d = pairs(i, f)
                d.blockLength = data
                data += (d.blockLengthSize * 2).toInt()
            }
            for (f in 0 until files) for (i in 0 until sides) {
                data = (data + 0x3F) and 0x3F.inv() // 64 byte alignment
                val d = pairs(i, f)
                d.data = data
                data += (d.blocksNum * d.sizeofBlock).toInt()
            }
        }

        /**
         * Groups of the encoding: the leading group (the kings and a third unique piece, or the leading pawns), then
         * the remaining pawns, then groups of equal pieces. [order] says in which order the groups are encoded.
         */
        private fun setGroups(d: PairsData, order: IntArray, file: Int) {
            var n = 0
            var firstLen = if (hasPawns) 0 else if (hasUniquePieces) 3 else 2
            d.groupLen[n] = 1
            for (i in 1 until pieceCount) {
                if (--firstLen > 0 || d.pieces[i] == d.pieces[i - 1]) d.groupLen[n]++
                else d.groupLen[++n] = 1
            }
            d.groupLen[++n] = 0
            val bothPawns = hasPawns && pawnCount[1] > 0
            var next = if (bothPawns) 2 else 1
            var freeSquares = 64 - d.groupLen[0] - if (bothPawns) d.groupLen[1] else 0
            var idx = 1L
            var k = 0
            while (next < n || k == order[0] || k == order[1]) {
                when (k) {
                    order[0] -> { // Leading pawns or pieces
                        d.groupIdx[0] = idx
                        idx *= if (hasPawns) LEAD_PAWNS_SIZE[d.groupLen[0]][file].toLong() else if (hasUniquePieces) 31332 else 462
                    }
                    order[1] -> { // Remaining pawns
                        d.groupIdx[1] = idx
                        idx *= BINOMIAL[d.groupLen[1]][48 - d.groupLen[0]]
                    }
                    else -> { // Remaining pieces
                        d.groupIdx[next] = idx
                        idx *= BINOMIAL[d.groupLen[next]][freeSquares]
                        freeSquares -= d.groupLen[next++]
                    }
                }
                k++
            }
            d.groupIdx[n] = idx
        }

        /** Sizes and Huffman tables of the compressed data (canonical Huffman code of "Recursive Pairing" symbols). */
        private fun setSizes(d: PairsData, start: Int): Int {
            var data = start
            d.flags = u8(data++)
            if (d.flags and SINGLE_VALUE != 0) {
                d.minSymLen = u8(data++) // The single value
                return data
            }
            val tbSize = d.groupIdx[d.groupLen.indexOf(0)]
            d.sizeofBlock = 1L shl u8(data++)
            d.span = 1L shl u8(data++)
            d.sparseIndexSize = (tbSize + d.span - 1) / d.span
            val padding = u8(data++)
            d.blocksNum = u32(data)
            data += 4
            d.blockLengthSize = d.blocksNum + padding
            d.maxSymLen = u8(data++)
            d.minSymLen = u8(data++)
            d.lowestSym = data
            val lengths = d.maxSymLen - d.minSymLen + 1
            d.base64 = LongArray(lengths)
            for (i in lengths - 2 downTo 0) {
                d.base64[i] = (d.base64[i + 1] + u16(d.lowestSym + 2 * i) - u16(d.lowestSym + 2 * (i + 1))) / 2
            }
            for (i in 0 until lengths) d.base64[i] = d.base64[i] shl (64 - i - d.minSymLen)
            data += lengths * 2
            d.symlen = IntArray(u16(data))
            data += 2
            d.btree = data
            val visited = BooleanArray(d.symlen.size)
            for (symbol in d.symlen.indices) {
                if (!visited[symbol]) d.symlen[symbol] = symbolLength(d, symbol, visited)
            }
            return data + d.symlen.size * 3 + (d.symlen.size and 1)
        }

        private fun left(d: PairsData, symbol: Int): Int = ((u8(d.btree + 3 * symbol + 1) and 0xF) shl 8) or u8(d.btree + 3 * symbol)
        private fun right(d: PairsData, symbol: Int): Int = (u8(d.btree + 3 * symbol + 2) shl 4) or (u8(d.btree + 3 * symbol + 1) ushr 4)

        /** Number of values (minus one) that a symbol expands to. */
        private fun symbolLength(d: PairsData, symbol: Int, visited: BooleanArray): Int {
            visited[symbol] = true
            val right = right(d, symbol)
            if (right == 0xFFF) return 0
            val left = left(d, symbol)
            if (!visited[left]) d.symlen[left] = symbolLength(d, left, visited)
            if (!visited[right]) d.symlen[right] = symbolLength(d, right, visited)
            return d.symlen[left] + d.symlen[right] + 1
        }

        /** DTZ value maps: the stored values are indexes into a map for each WDL result. */
        private fun setDtzMap(start: Int): Int {
            var data = start
            map = data
            for (f in 0 until files) {
                val d = pairs(0, f)
                if (d.flags and MAPPED == 0) continue
                if (d.flags and WIDE != 0) {
                    data += data and 1
                    for (i in 0 until 4) {
                        d.mapIdx[i] = (data - map) / 2 + 1
                        data += 2 * u16(data) + 2
                    }
                } else {
                    for (i in 0 until 4) {
                        d.mapIdx[i] = data - map + 1
                        data += u8(data) + 1
                    }
                }
            }
            return data + (data and 1)
        }

        /** Value at [index] of the compressed data. */
        private fun decompress(d: PairsData, index: Long): Int {
            if (d.flags and SINGLE_VALUE != 0) return d.minSymLen
            val k = (index / d.span).toInt()
            var block = u32(d.sparseIndex + SPARSE_ENTRY_SIZE * k).toInt()
            var offset = u16(d.sparseIndex + SPARSE_ENTRY_SIZE * k + 4)
            offset += (index % d.span - d.span / 2).toInt()
            while (offset < 0) offset += u16(d.blockLength + 2 * --block) + 1
            while (offset > u16(d.blockLength + 2 * block)) offset -= u16(d.blockLength + 2 * block++) + 1

            var pointer = (d.data + block.toLong() * d.sizeofBlock).toInt()
            var buffer64 = u64BigEndian(pointer)
            pointer += 8
            var bufferSize = 64
            var symbol: Int
            while (true) {
                var length = 0
                while (java.lang.Long.compareUnsigned(buffer64, d.base64[length]) < 0) length++
                symbol = ((buffer64 - d.base64[length]) ushr (64 - length - d.minSymLen)).toInt()
                symbol += u16(d.lowestSym + 2 * length)
                if (offset < d.symlen[symbol] + 1) break
                offset -= d.symlen[symbol] + 1
                length += d.minSymLen
                buffer64 = buffer64 shl length
                bufferSize -= length
                if (bufferSize <= 32) {
                    bufferSize += 32
                    buffer64 = buffer64 or (u32BigEndian(pointer) shl (64 - bufferSize))
                    pointer += 4
                }
            }
            // The symbol expands to symlen + 1 values: descend the pairs to the one at the offset
            while (d.symlen[symbol] != 0) {
                val left = left(d, symbol)
                if (offset < d.symlen[left] + 1) symbol = left
                else {
                    offset -= d.symlen[left] + 1
                    symbol = right(d, symbol)
                }
            }
            return left(d, symbol)
        }

        /** Probes the position; null if it fails. See [probeTable]. */
        fun probe(position: Position, blackStronger: Boolean, wdl: Int): Pair<Int, State>? {
            open()
            val symmetricBlackToMove = symmetric && position.sideToMove == BLACK
            val flip = symmetricBlackToMove || blackStronger
            val flipColor = if (flip) 8 else 0
            val flipSquares = if (flip) 56 else 0
            val stm = (if (flip) 1 else 0) xor position.sideToMove

            val squares = IntArray(MAX_PIECES)
            val pieces = IntArray(MAX_PIECES)
            var size = 0
            var leadPawnsCount = 0
            var leadPawns = 0L
            var tbFile = 0

            if (hasPawns) {
                // Pawns of the leading color first: the one with the highest MAP_PAWNS leads
                val leadingCode = pairs(0, 0).pieces[0] xor flipColor
                val leadingColor = if (leadingCode and 8 != 0) BLACK else WHITE
                leadPawns = position.pieces(leadingColor, PAWN)
                leadPawns.forEachSquare { squares[size++] = it xor flipSquares }
                leadPawnsCount = size
                var lead = 0
                for (i in 1 until leadPawnsCount) if (MAP_PAWNS[squares[i]] > MAP_PAWNS[squares[lead]]) lead = i
                squares[0] = squares[lead].also { squares[lead] = squares[0] }
                tbFile = minOf(fileOf(squares[0]), 7 - fileOf(squares[0]))
            }

            if (kind == Kind.DTZ) {
                val flags = pairs(stm, tbFile).flags
                if ((flags and STM) != stm && !(symmetric && !hasPawns)) return 0 to State.CHANGE_STM
            }

            (position.occupied and leadPawns.inv()).forEachSquare { square ->
                squares[size] = square xor flipSquares
                pieces[size++] = pieceCode(position.pieceOn(square)) xor flipColor
            }

            val d = pairs(stm, tbFile)
            // Same order of pieces as the table
            for (i in leadPawnsCount until size - 1) {
                for (j in i + 1 until size) {
                    if (d.pieces[i] == pieces[j]) {
                        pieces[i] = pieces[j].also { pieces[j] = pieces[i] }
                        squares[i] = squares[j].also { squares[j] = squares[i] }
                        break
                    }
                }
            }
            // The leading piece in the a1-d1-d4 triangle (or the a-d files for pawns)
            if (fileOf(squares[0]) > 3) for (i in 0 until size) squares[i] = squares[i] xor 7

            var index: Long
            if (hasPawns) {
                index = LEAD_PAWN_IDX[leadPawnsCount][squares[0]].toLong()
                sortBy(squares, 1, leadPawnsCount) { MAP_PAWNS[it] }
                for (i in 1 until leadPawnsCount) index += BINOMIAL[i][MAP_PAWNS[squares[i]]]
            } else {
                if (rankOf(squares[0]) > 3) for (i in 0 until size) squares[i] = squares[i] xor 56
                for (i in 0 until d.groupLen[0]) {
                    val off = offDiagonal(squares[i])
                    if (off == 0) continue
                    if (off > 0) for (j in i until size) squares[j] = ((squares[j] ushr 3) or (squares[j] shl 3)) and 63
                    break
                }
                index = if (hasUniquePieces) {
                    val adjust1 = if (squares[1] > squares[0]) 1 else 0
                    val adjust2 = (if (squares[2] > squares[0]) 1 else 0) + (if (squares[2] > squares[1]) 1 else 0)
                    when {
                        offDiagonal(squares[0]) != 0 ->
                            ((MAP_A1D1D4[squares[0]] * 63L + (squares[1] - adjust1)) * 62 + squares[2] - adjust2)
                        offDiagonal(squares[1]) != 0 ->
                            ((6 * 63L + rankOf(squares[0]) * 28 + MAP_B1H1H7[squares[1]]) * 62 + squares[2] - adjust2)
                        offDiagonal(squares[2]) != 0 ->
                            6 * 63L * 62 + 4 * 28 * 62 + rankOf(squares[0]) * 7 * 28 + (rankOf(squares[1]) - adjust1) * 28 + MAP_B1H1H7[squares[2]]
                        else ->
                            6 * 63L * 62 + 4 * 28 * 62 + 4 * 7 * 28 + rankOf(squares[0]) * 7 * 6 + (rankOf(squares[1]) - adjust1) * 6 + (rankOf(squares[2]) - adjust2)
                    }
                } else {
                    MAP_KK[MAP_A1D1D4[squares[0]]][squares[1]].toLong()
                }
            }

            // Remaining groups: each one as a combination of squares, skipping the squares of the previous groups
            index *= d.groupIdx[0]
            var groupStart = d.groupLen[0]
            var remainingPawns = hasPawns && pawnCount[1] > 0
            var next = 0
            while (d.groupLen[++next] != 0) {
                val length = d.groupLen[next]
                sortBy(squares, groupStart, groupStart + length) { it }
                var n = 0L
                for (i in 0 until length) {
                    val square = squares[groupStart + i]
                    var adjust = 0
                    for (j in 0 until groupStart) if (square > squares[j]) adjust++
                    n += BINOMIAL[i + 1][square - adjust - if (remainingPawns) 8 else 0]
                }
                remainingPawns = false
                index += n * d.groupIdx[next]
                groupStart += length
            }

            val value = decompress(d, index)
            return mapScore(tbFile, value, wdl) to State.OK
        }

        private fun mapScore(file: Int, value: Int, wdl: Int): Int {
            if (kind == Kind.WDL) return value - 2
            val d = pairs(0, file)
            var dtz = value
            if (d.flags and MAPPED != 0) {
                val i = d.mapIdx[WDL_MAP[wdl + 2]]
                dtz = if (d.flags and WIDE != 0) u16(map + 2 * (i + value)) else u8(map + i + value)
            }
            // DTZ tables store moves or plies: return plies
            if ((wdl == WIN && d.flags and WIN_PLIES == 0) || (wdl == LOSS && d.flags and LOSS_PLIES == 0) ||
                wdl == CURSED_WIN || wdl == BLESSED_LOSS) {
                dtz *= 2
            }
            return dtz + 1
        }
    }

    companion object {
        const val LOSS = -2
        const val BLESSED_LOSS = -1
        const val DRAW = 0
        const val CURSED_WIN = 1
        const val WIN = 2

        private const val MAX_PIECES = 7
        private const val SPARSE_ENTRY_SIZE = 6

        // Flags of the pairs data
        private const val STM = 1
        private const val MAPPED = 2
        private const val WIN_PLIES = 4
        private const val LOSS_PLIES = 8
        private const val WIDE = 16
        private const val SINGLE_VALUE = 128

        private val WDL_MAP = intArrayOf(1, 3, 0, 2, 0)

        /** Piece codes of the tables: pawn 1 to king 6, plus 8 for black. */
        private fun pieceCode(piece: Int): Int = (typeOf(piece) + 1) or if (colorOf(piece) == BLACK) 8 else 0

        /** Rank minus file: 0 on the a1-h8 diagonal, negative below it. */
        private fun offDiagonal(square: Int): Int = rankOf(square) - fileOf(square)

        private inline fun sortBy(array: IntArray, from: Int, to: Int, crossinline key: (Int) -> Int) {
            // Stable insertion sort of a few elements
            for (i in from + 1 until to) {
                val value = array[i]
                var j = i - 1
                while (j >= from && key(array[j]) > key(value)) {
                    array[j + 1] = array[j]
                    j--
                }
                array[j + 1] = value
            }
        }

        /** Squares below the a1-h8 diagonal, numbered 0 to 27. */
        private val MAP_B1H1H7 = IntArray(64)
        /** Squares of the a1-d1-d4 triangle, numbered 0 to 9 (the diagonal last). */
        private val MAP_A1D1D4 = IntArray(64)
        /** The 462 legal placements of two kings with the first one in the a1-d1-d4 triangle. */
        private val MAP_KK = Array(10) { IntArray(64) }
        private val BINOMIAL = Array(MAX_PIECES) { LongArray(64) }
        /** Pawn squares (a2 to h7) numbered from the edges and the lower ranks: the highest leads. */
        private val MAP_PAWNS = IntArray(64)
        private val LEAD_PAWN_IDX = Array(MAX_PIECES) { IntArray(64) }
        private val LEAD_PAWNS_SIZE = Array(MAX_PIECES) { IntArray(4) }

        init {
            var code = 0
            for (square in 0 until 64) if (offDiagonal(square) < 0) MAP_B1H1H7[square] = code++

            code = 0
            val diagonal = mutableListOf<Int>()
            for (square in 0..27) { // a1 to d4
                if (offDiagonal(square) < 0 && fileOf(square) <= 3) MAP_A1D1D4[square] = code++
                else if (offDiagonal(square) == 0 && fileOf(square) <= 3) diagonal.add(square)
            }
            for (square in diagonal) MAP_A1D1D4[square] = code++

            code = 0
            val bothOnDiagonal = mutableListOf<Pair<Int, Int>>()
            for (idx in 0 until 10) {
                for (s1 in 0..27) {
                    if (MAP_A1D1D4[s1] != idx || (idx == 0 && s1 != 1)) continue // b1 is 0
                    for (s2 in 0 until 64) {
                        val adjacent = Math.abs(fileOf(s1) - fileOf(s2)) <= 1 && Math.abs(rankOf(s1) - rankOf(s2)) <= 1
                        when {
                            adjacent -> continue // Illegal position
                            offDiagonal(s1) == 0 && offDiagonal(s2) > 0 -> continue // First on the diagonal, second above
                            offDiagonal(s1) == 0 && offDiagonal(s2) == 0 -> bothOnDiagonal.add(idx to s2)
                            else -> MAP_KK[idx][s2] = code++
                        }
                    }
                }
            }
            for ((idx, s2) in bothOnDiagonal) MAP_KK[idx][s2] = code++

            BINOMIAL[0][0] = 1
            for (n in 1 until 64) {
                for (k in 0 until minOf(MAX_PIECES, n + 1)) {
                    BINOMIAL[k][n] = (if (k > 0) BINOMIAL[k - 1][n - 1] else 0) + (if (k < n) BINOMIAL[k][n - 1] else 0)
                }
            }

            var available = 47
            for (leadPawns in 1 until MAX_PIECES - 1) {
                for (file in 0 until 4) {
                    var idx = 0
                    for (rank in 1..6) {
                        val square = rank * 8 + file
                        if (leadPawns == 1) {
                            MAP_PAWNS[square] = available--
                            MAP_PAWNS[square xor 7] = available--
                        }
                        LEAD_PAWN_IDX[leadPawns][square] = idx
                        idx += BINOMIAL[leadPawns - 1][MAP_PAWNS[square]].toInt()
                    }
                    LEAD_PAWNS_SIZE[leadPawns][file] = idx
                }
            }
        }
    }
}

package core

import core.Player.Declarations.CHESSY
import core.Player.Declarations.PLAYER
import evaluation.MixedCacheEvaluator
import evaluation.WeightEvaluator
import exceptions.InvalidMovementException
import exceptions.NotationException
import me.carleslc.kotlin.extensions.time.measureAndPrint
import search.search
import java.io.File
import java.util.concurrent.TimeUnit

val EVALUATOR = MixedCacheEvaluator(WeightEvaluator(2), WeightEvaluator(3))

val TEST = "test"
val LAST = File("tests/last.board")

fun main() {
    var board = if (TEST.isNotBlank()) Board.parseFile("tests/$TEST.board") else Board.new()
    var endGame = board.isEndGame()

    println("Game Started\n")
    println(board)

    while (!endGame) {
        status(board)
        println()
        println("Legal Moves (${board.legalMoves.size}): ${board.legalMoves}")
        println()
        val move = if (board.turn.isChessy()) playChessy(board) else playPlayer(board)
        println(move)
        println()
        board = move.nextBoard
        println(board)
        endGame = board.isEndGame()
    }

    status(board)
}

fun save(board: Board) {
    if (!LAST.exists()) {
        LAST.parentFile.mkdirs()
        LAST.createNewFile()
    }
    LAST.writeText(board.toString())
}

fun status(board: Board) {
    board.printStatus()
    if (board.turn == Player.WHITE) {
        save(board)
    }
}

fun playPlayer(board: Board): Move {
    println("PLAYER TURN ($PLAYER), Score: ${EVALUATOR.getScore(board)}\n")
    var valid = false
    var move: Move? = null
    while (!valid) {
        try {
            println("What is your move?")
            print("FROM: ")
            val from = Position.fromAlgebraicChessNotation(readLine()!!)
            print("TO: ")
            val to = Position.fromAlgebraicChessNotation(readLine()!!)
            move = Move(from, to, board)
            if (move !in board.legalMoves) {
                throw InvalidMovementException()
            }
            valid = true
        } catch (e: Exception) {
            if (e is NotationException || e is InvalidMovementException) {
                println(e.message)
            } else {
                throw e
            }
        }
    }
    return move!!
}

fun playChessy(board: Board): Move {
    println("CHESSY TURN ($CHESSY), Score: ${EVALUATOR.getScore(board)}\n")
    println("Thinking...")
    return measureAndPrint(TimeUnit.MILLISECONDS) {
        search(board).move
    }
}
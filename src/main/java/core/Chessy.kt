package core

import core.Player.Declarations.CHESSY
import core.Player.Declarations.PLAYER
import evaluation.WeightEvaluator
import exceptions.InvalidMovementException
import exceptions.NotationException
import me.carleslc.kotlin.extensions.time.measureAndPrint
import search.MixedCacheEvaluator
import search.search
import search.searchDebug
import java.util.concurrent.TimeUnit

val EVALUATOR = MixedCacheEvaluator(WeightEvaluator(2), WeightEvaluator(3))
val PARALLEL = false

val TEST = "begin"

fun main(args: Array<String>) {
    var board = if (TEST.isNotBlank()) Board.parseFile("tests/$TEST.board") else Board.new()
    var endGame = board.isEndGame()

    println("Game Started\n")
    println(board)

    while (!endGame) {
        board.printStatus()
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

    board.printStatus()
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
        searchDebug(board, 1).move
    }
}
package core

import exceptions.InvalidMovementException
import exceptions.NotationException
import me.carleslc.kotlin.extensions.collections.getRandom
import java.util.*

val CHESSY = Player.WHITE
val PLAYER = CHESSY.opponent()

fun main(args: Array<String>) {
    var endGame = false
    var board = Board.new()

    println("Game Started\n")
    println(board)

    while (!endGame) {
        board.printStatus()
        println()
        val move = if (board.turn.isChessy()) playChessy(board) else playPlayer(board)
        println()
        println(move)
        println()
        board = move.nextBoard
        println(board)
        endGame = board.isEndGame()
    }

    board.printStatus()
}

fun playPlayer(board: Board): Move {
    println("PLAYER TURN ($PLAYER), Score: ${board.getScore()}\n")
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
            if (!board.isValid(move, PLAYER)) {
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
    println("CHESSY TURN ($CHESSY), Score: ${board.getScore()}\n")
    println("Thinking...")

    // TODO

    // Testing
    val moves = ArrayList(board.moves)
    return moves.getRandom()
}
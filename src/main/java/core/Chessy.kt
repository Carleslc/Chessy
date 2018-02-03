package core

val CHESSY = Player.WHITE

fun main(args: Array<String>) {
    var endGame = false
    var board = Board.new()

    while (!endGame) {
        val move = if (board.turn.isChessy()) playChessy(board) else playPlayer(board)
        board = move.nextBoard
        endGame = board.isEndGame()
    }

    // TODO: ScoreTerminal results
}

fun playPlayer(board: Board): Move {
    println("PLAYER TURN")
    TODO()
}

fun playChessy(board: Board): Move {
    println("CHESSY TURN")
    println("Thinking...")
    TODO()
}
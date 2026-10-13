package chessy

import chessy.core.Perft
import chessy.core.Position
import kotlin.system.exitProcess

private const val USAGE = """Usage: chessy <command> [arguments]

Commands:
  perft <depth> [fen]    Count the leaf nodes of the legal move tree (default: starting position)
  divide <depth> [fen]   Perft for each legal move of the position
  d [fen]                Display the position"""

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        return
    }
    try {
        when (args[0]) {
            "perft" -> perft(depth(args), position(args, 2))
            "divide" -> divide(depth(args), position(args, 2))
            "d" -> println(position(args, 1))
            else -> throw IllegalArgumentException("Unknown command: ${args[0]}")
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(USAGE)
        exitProcess(1)
    }
}

private fun depth(args: Array<String>): Int =
    args.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Missing or invalid depth")

/** The FEN is the rest of the arguments from [index], so it can be passed with or without quotes. */
private fun position(args: Array<String>, index: Int): Position =
    if (args.size > index) Position.fromFen(args.drop(index).joinToString(" ")) else Position.startingPosition()

private fun perft(depth: Int, position: Position) {
    val start = System.nanoTime()
    val nodes = Perft(position).perft(depth)
    printNodes(nodes, System.nanoTime() - start)
}

private fun divide(depth: Int, position: Position) {
    val start = System.nanoTime()
    val results = Perft(position).divide(depth)
    for ((move, nodes) in results.sortedBy { it.first.toString() }) {
        println("$move: $nodes")
    }
    println()
    printNodes(results.sumOf { it.second }, System.nanoTime() - start)
}

private fun printNodes(nodes: Long, nanos: Long) {
    val millis = nanos / 1_000_000
    val nps = if (nanos > 0) nodes * 1_000_000_000 / nanos else 0
    println("Nodes: $nodes")
    println("Time: $millis ms")
    println("NPS: $nps")
}

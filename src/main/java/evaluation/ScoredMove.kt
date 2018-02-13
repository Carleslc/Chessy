package evaluation

import core.Move

data class ScoredMove(val move: Move, val score: Int) : Comparable<ScoredMove> {

    override operator fun compareTo(other: ScoredMove) = score.compareTo(other.score)

}
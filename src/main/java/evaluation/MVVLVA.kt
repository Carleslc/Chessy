package evaluation

import core.Move
import pieces.PieceType

object MVVLVA {

    private val victimAggressorTable = arrayOf(
            // v.        P     N      B      R      Q      K     // a.
            intArrayOf(6002, 20225, 20250, 20400, 20800, 26900), // P
            intArrayOf(4775,  6004, 20025, 20175, 20575, 26675), // N
            intArrayOf(4750,  4975,  6006, 20150, 20550, 26650), // B
            intArrayOf(4600,  4825,  4850,  6008, 20400, 26500), // R
            intArrayOf(4200,  4425,  4450,  4600,  6010, 26100), // Q
            intArrayOf(3100,  3325,  3350,  3500,  3900, 26000)  // K
    )

    fun getScore(move: Move) = with (move) { if (isCapture()) get(target!!.type, origin!!.type) else 0 }

    operator fun get(victim: PieceType, aggressor: PieceType) = victimAggressorTable[aggressor.ordinal][victim.ordinal]

}
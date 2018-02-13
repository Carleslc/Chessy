package evaluation

import me.carleslc.kotlin.extensions.arrays.array2dOf
import pieces.PieceType

object MVVLVA {

    private val victimAggressorTable = array2dOf(
            // v.     P     N      B      R      Q      K     // a.
            arrayOf(6002, 20225, 20250, 20400, 20800, 26900), // P
            arrayOf(4775,  6004, 20025, 20175, 20575, 26675), // N
            arrayOf(4750,  4975,  6006, 20150, 20550, 26650), // B
            arrayOf(4600,  4825,  4850,  6008, 20400, 26500), // R
            arrayOf(4200,  4425,  4450,  4600,  6010, 26100), // Q
            arrayOf(3100,  3325,  3350,  3500,  3900, 26000)  // K
    )

    operator fun get(victim: PieceType, aggressor: PieceType) = victimAggressorTable[aggressor.ordinal][victim.ordinal]

}
package search

var PawnTable = arrayOf(
        intArrayOf(65, 65, 65, 65, 65, 65, 65, 65),
        intArrayOf(50, 50, 50, 50, 50, 50, 50, 50),
        intArrayOf(10, 10, 20, 30, 30, 20, 10, 10),
        intArrayOf(5, 5, 10, 27, 27, 10, 5, 5),
        intArrayOf(0, 0, 0, 25, 25, 0, 0, 0),
        intArrayOf(5, -5, -10, 0, 0, -10, -5, 5),
        intArrayOf(5, 10, 10, -25, -25, 10, 10, 5),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0))

var KnightTable = arrayOf(
        intArrayOf(-50, -40, -30, -30, -30, -30, -40, -50),
        intArrayOf(-40, -20, 0, 0, 0, 0, -20, -40),
        intArrayOf(-30, 0, 10, 15, 15, 10, 0, -30),
        intArrayOf(-30, 50, 15, 20, 20, 15, 50, -30),
        intArrayOf(-30, 0, 15, 20, 20, 15, 0, -30),
        intArrayOf(-30, 50, 10, 15, 15, 10, 50, -30),
        intArrayOf(-40, -20, 0, 50, 50, 0, -20, -40),
        intArrayOf(-50, -40, -20, -30, -30, -20, -40, -50))

var BishopTable = arrayOf(
        intArrayOf(-20, -10, -10, -10, -10, -10, -10, -20),
        intArrayOf(-10, 0, 0, 0, 0, 0, 0, -10),
        intArrayOf(-10, 0, 50, 10, 10, 50, 0, -10),
        intArrayOf(-10, 50, 50, 10, 10, 50, 50, -10),
        intArrayOf(-10, 0, 10, 10, 10, 10, 0, -10),
        intArrayOf(-10, 10, 10, 10, 10, 10, 10, -10),
        intArrayOf(-10, 50, 0, 0, 0, 0, 50, -10),
        intArrayOf(-20, -10, -40, -10, -10, -40, -10, -20))

var KingTable = arrayOf(
        intArrayOf(-30, -40, -40, -50, -50, -40, -40, -30),
        intArrayOf(-30, -40, -40, -50, -50, -40, -40, -30),
        intArrayOf(-30, -40, -40, -50, -50, -40, -40, -30),
        intArrayOf(-30, -40, -40, -50, -50, -40, -40, -30),
        intArrayOf(-20, -30, -30, -40, -40, -30, -30, -20),
        intArrayOf(-10, -20, -20, -20, -20, -20, -20, -10),
        intArrayOf(20, 20, 0, 0, 0, 0, 20, 20),
        intArrayOf(20, 30, 10, 0, 0, 10, 30, 20))

var KingTableEndGame = arrayOf(
        intArrayOf(-50, -40, -30, -20, -20, -30, -40, -50),
        intArrayOf(-30, -20, -10, 0, 0, -10, -20, -30),
        intArrayOf(-30, -10, 20, 30, 30, 20, -10, -30),
        intArrayOf(-30, -10, 30, 40, 40, 30, -10, -30),
        intArrayOf(-30, -10, 30, 40, 40, 30, -10, -30),
        intArrayOf(-30, -10, 20, 30, 30, 20, -10, -30),
        intArrayOf(-30, -30, 0, 0, 0, 0, -30, -30),
        intArrayOf(-50, -30, -30, -30, -30, -30, -30, -50))

var Zero = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
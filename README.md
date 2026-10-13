# Chessy
Artificial Intelligence playing Chess with Minimax Alpha-Beta prunning algorithm.

This project is not designed for professional usage, this is only for personal learning. For professional purposes use advanced chess engines like Stockfish.

> This is the first implementation (2018-2019), kept as a reference in the `v0` branch.
> The new engine lives in `master`.

## Play

Requires JDK and Maven. Moves are written in standard algebraic notation (`e4`, `Nf3`, `exd6`, `O-O`, `a8=N`) or as `e2e4`.

```
mvn compile exec:java
mvn compile exec:java -Dexec.args="--black --time 10 --pgn games/last.pgn"
```

```
Usage: chessy [options]
  --black          Chessy plays black (default: white)
  --auto           Chessy plays both sides
  --human          Human plays both sides
  --fen <fen>      Start from a position in FEN notation (between quotes)
  --board <file>   Start from a board file (see tests/test.board)
  --turn <color>   Player to move first with --board: white (default) or black
  --depth <n>      Maximum search depth (default: no limit, only time)
  --time <s>       Maximum seconds to think per move (default: 5)
  --pgn <file>     Save the game in PGN notation to this file
```

The game is also printed in PGN at the end, to analyze it in other platforms (Lichess, Chess.com...).

## Test

```
mvn test
```

Not implemented: UCI.

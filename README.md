# Chessy
Artificial Intelligence playing Chess with Minimax Alpha-Beta prunning algorithm.

This project is not designed for professional usage, this is only for personal learning. For professional purposes use advanced chess engines like Stockfish.

> This is the first implementation (2018-2019), kept as a reference in the `kotlin-old` branch.
> The new engine lives in `master`.

## Play

Requires JDK and Maven. Moves are written like `e2e4`.

```
mvn compile exec:java
mvn compile exec:java -Dexec.args="--black --depth 4 --time 10"
```

```
Usage: chessy [options]
  --black          Chessy plays black (default: white)
  --auto           Chessy plays both sides
  --human          Human plays both sides
  --board <file>   Start from a board file (see tests/test.board)
  --turn <color>   Player to move first with --board: white (default) or black
  --depth <n>      Maximum search depth (default: 4)
  --time <s>       Maximum seconds to think per move (default: 10)
```

Not implemented: castling, en passant, under-promotion and UCI.

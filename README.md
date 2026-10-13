# Chessy

A chess engine with a "constrictor" playing style: it gains space, controls squares and restricts the opponent's mobility.

This project is for personal learning. For professional purposes use advanced chess engines like Stockfish.

See the [PRD](docs/PRD.md) for the design and roadmap.

## Structure

- [`engine/`](engine): the engine, in Kotlin (bitboards, UCI).
- [`tools/`](tools): Python tooling around the engine (board and game viewer, analysis scripts, lichess-bot).
- [`docs/`](docs): documentation.

The first implementation (2018-2019) is kept in the `kotlin-old` branch.

## Engine

Requires JDK 17+.

```
cd engine
./gradlew test
./gradlew installDist
build/install/chessy/bin/chessy perft 6 --threads 8
build/install/chessy/bin/chessy play --pgn games/last.pgn
```

```
Commands:
  perft <depth> [fen]    Count the leaf nodes of the legal move tree (default: starting position)
  divide <depth> [fen]   Perft for each legal move of the position
  d [fen]                Display the position
  moves [fen]            Legal moves of the position, in SAN and UCI notation
  play                   Play a game in the console (moves in SAN or UCI notation)
  pgn <file>             Read the games of a PGN file and write them again (validates the moves)

Options:
  --threads <n>          Threads for perft and divide (default: available processors)
  --fen <fen>            Starting position of play (between quotes)
  --pgn <file>           Save the game played to this PGN file
```

Games are saved in PGN, so they can be opened with the viewer (`python tools/game.py games/last.pgn`) or analyzed in Lichess or Chess.com.

# Chessy

A chess engine with a "constrictor" playing style: it gains space, controls squares and restricts the opponent's mobility.

This project is for personal learning. For professional purposes use advanced chess engines like Stockfish.

See the [PRD](docs/PRD.md) for the design and roadmap.

## Structure

- [`engine/`](engine): the engine, in Kotlin (bitboards, UCI).
- [`tools/`](tools): Python tooling around the engine (board and game viewer, analysis scripts, lichess-bot).
- [`docs/`](docs): documentation.

The first implementation (2018-2019) is kept in the `v0` branch, and the 2022 viewer in the `python-old` branch.

## Engine

Requires JDK 17+.

```
cd engine
./gradlew test
./gradlew installDist
build/install/chessy/bin/chessy play --time 3 --pgn games/last.pgn
build/install/chessy/bin/chessy bench
build/install/chessy/bin/chessy perft 6 --threads 8
```

Without a command, `chessy` speaks the [UCI](https://www.chessprogramming.org/UCI) protocol, so it can be used from any chess GUI, from `tools/match.py` or on Lichess with [lichess-bot](tools/lichess).

```
Usage: chessy [command] [arguments] [options]

Commands:
  perft <depth> [fen]    Count the leaf nodes of the legal move tree (default: starting position)
  divide <depth> [fen]   Perft for each legal move of the position
  d [fen]                Display the position
  moves [fen]            Legal moves of the position, in SAN and UCI notation
  eval [fen]             Evaluation of the position, term by term
  bench [depth]          Search fixed positions to a fixed depth (default: 6), to measure the speed
  play                   Play a game against Chessy in the console (moves in SAN or UCI notation)
  pgn <file>             Read the games of a PGN file and write them again (validates the moves)

Options:
  --threads <n>          Threads for perft and divide (default: available processors)
  --evaluator <name>     Evaluator for eval, bench and play: Material (default: Material)
  --search <name>        Search of play and bench: alphabeta (default) or greedy
  --depth <n>            Maximum depth of the search in play (fixed depth for greedy, default: 2)
  --time <s>             Seconds to think per move in play (default: 3)
  --fen <fen>            Starting position of play (between quotes)
  --chessy <color>       Pieces played by Chessy in play: white, black (default), both or none
  --pgn <file>           Save the game played to this PGN file
```

Chessy searches with alpha-beta and iterative deepening, and evaluates with the baseline material evaluator until the "constrictor" evaluation of phase 5.
The greedy search (fixed depth, only material) is kept as a reference player (UCI options `Search` and `Greedy Depth`).

Games are saved in PGN, so they can be opened with the viewer (`python tools/game.py games/last.pgn`) or analyzed in Lichess or Chess.com.

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
  bench [depth]          Search fixed positions to a fixed depth (default: 12), to measure the speed
  play                   Play a game against Chessy in the console (moves in SAN or UCI notation)
  pgn <file>             Read the games of a PGN file and write them again (validates the moves)

Options:
  --threads <n>          Threads for perft and divide (default: available processors), and for bench and play (default: 1)
  --style <name>         Playing style of eval, bench and play: space (default, the constrictor), material or greedy
  --depth <n>            Maximum depth of the search in play (fixed depth for greedy, default: 2)
  --time <s>             Seconds to think per move in play (default: 3)
  --fen <fen>            Starting position of play (between quotes)
  --chessy <color>       Pieces played by Chessy in play: white, black (default), both or none
  --pgn <file>           Save the game played to this PGN file
```

Chessy searches with alpha-beta (iterative deepening, transposition table, principal variation search, null move pruning, late move reductions, futility pruning, static exchange evaluation and Lazy SMP for several threads),
and has three playing styles (UCI option `Style`, `--style` in the command line):

- **Space** (default): the "constrictor" evaluation. Space, square control, safe mobility and restriction of the opponent,
  with the value of each piece depending on its activity (see `chessy eval`).
- **Material**: material and piece-square tables, the baseline evaluation.
- **Greedy**: a fixed-depth search counting only material (`Greedy Depth`), the weakest.

Other UCI options: `Hash` (MB), `Threads`, `Move Overhead` and `Greedy Depth`.

Games are saved in PGN, so they can be opened with the viewer (`python tools/game.py games/last.pgn`) or analyzed in Lichess or Chess.com.

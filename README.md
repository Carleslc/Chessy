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
  syzygy [fen]           Probe the Syzygy tablebases (--syzygy path): WDL and DTZ of the position, or of each FEN of the input
  weights                Weights of the Space style (or the Full style with --style full), in the format of a weights file
  tune <file>            Tune the weights of the Space style (or Full) with the positions of a training file (Texel's method)

Options:
  --threads <n>          Threads for perft and divide (default: available processors), and for bench and play (default: 1)
  --style <name>         Playing style of eval, bench and play: space (default, the constrictor), full (the strongest),
                         material or greedy (the weakest)
  --depth <n>            Maximum depth of the search in play (fixed depth for greedy, default: 2)
  --elo <elo>            Play at this Elo (900 to 2800, in steps of 100) instead of the full strength of the style
  --book <on|off|file>   Opening book of play: the books of Chessy (default), none, or a Polyglot book file
  --opening <name>       Play this opening of the books of Chessy: KingsIndian, Benoni, French, CaroKann, Sicilian,
                         QueensGambit, RuyLopez or Pirc
  --chess960 <n>         Chess960: starting position number (0 to 959), random, or true (with --fen, and in divide and moves)
  --time <s>             Seconds to think per move in play (default: 3)
  --fen <fen>            Starting position of play (between quotes)
  --chessy <color>       Pieces played by Chessy in play: white, black (default), both or none
  --pgn <file>           Save the game played to this PGN file
  --syzygy <path>        Folder of the Syzygy tablebases (several separated by the path separator)
  --weights <file>       Weights file of the Space or Full style (default: the tuned weights), also the starting point of tune
  (and the options of tune: --output, --positions, --passes, --all, --freeze, --max-change)
```

Chessy searches with alpha-beta (iterative deepening, transposition table, principal variation search, null move pruning
with verification, late move reductions, futility pruning, static exchange evaluation, killer, history and countermove
ordering, safe check extension and Lazy SMP for several threads), and has four playing styles
(UCI option `Style`, `--style` in the command line):

- **Space** (default): the "constrictor" evaluation. Space, square control, safe mobility and restriction of the opponent,
  with the value of each piece depending on its activity (see `chessy eval`). Its weights are tuned with Texel's method,
  keeping the style.
- **Full**: the same evaluation with every weight tuned for strength, including the weights of the style:
  it plays less "constrictor" (less space, fewer trapped pieces) but it is the strongest in direct games.
- **Material**: material and piece-square tables, the baseline evaluation.
- **Greedy**: a fixed-depth search counting only material (`Greedy Depth`, 2 by default), the weakest.

And besides:

- **Strength levels**: `UCI_LimitStrength` and `UCI_Elo` (`--elo` in the command line) play at an Elo from 900 to 2800,
  in steps of 100 (see *Versions and styles*).
- **Opening book** (`OwnBook`, off by default as rating lists require; on by default in `play`): Polyglot books of openings
  where one side gains space (King's Indian, Benoni, French, Advance variations, closed Queen's Pawn, closed Ruy Lopez...),
  built with [`tools/book.py`](tools/book.py). `Opening` plays only one of them (`--opening`), and `Book File` another book.
- **Endgame tablebases**: the [Syzygy tablebases](https://syzygy-tables.info/) of up to 5 pieces (download them into
  `tools/syzygy` as explained in [tools](tools/README.md)): exact results of the endgames in the search, and perfect
  conversion of won endgames before the fifty-move rule. When they are downloaded they are used by default, in `play`
  and in UCI (`SyzygyPath` is `tools/syzygy` by default); `--syzygy off` or an empty `SyzygyPath` play without them.
- **Chess960**: `UCI_Chess960` (`--chess960` in the command line).
- **Pondering**: `Ponder`, thinking on the opponent's time.

Other UCI options: `Hash` (MB), `Threads`, `Move Overhead`, `Greedy Depth`, `SyzygyProbeDepth` and `Weights`
(a weights file for Space or Full).

`./gradlew distZip` packages the engine in `build/distributions/chessy-<version>.zip`, with the launchers `bin/chessy`
(macOS, Linux) and `bin/chessy.bat` (Windows), to install it anywhere with Java 17+.

## Versions and styles

Strength measured against Stockfish 19 with limited strength (`UCI_LimitStrength`, `UCI_Elo`), 100 games per level
at 10+0.1 (v0: 40 games per level at 60+0.6), 1 thread, with the balanced openings of `tools/openings/balanced.epd`.
The `UCI_Elo` scale of Stockfish is anchored to the [CCRL Blitz](https://computerchess.org.uk/404/) list,
where Chessy would be ~100 Elo higher (see the CCRL estimate below, measured at 2+1).

| | Elo (Stockfish scale) | Against Stockfish | WAC (1 s) | STS (0.2 s) | STS Elo |
|---|---|---|---|---|---|
| **v0** (2018–2019, console only) | ~1500 | 87.5% vs 1320, 25% vs 1600 | 122/300 | 6735 | ~1750 |
| **v1 Greedy** (depth 2) | ~900 | 7% vs 1320; v0 wins 97% | 35/300 | 5556 | 1406 |
| **v1 Greedy** (depth 3) | ~1100 | 22% vs 1320 (50 games) | 91/300 | 5427 | 1368 |
| **v1 Greedy** (depth 4) | ~1120 | 24% vs 1320 (50 games) | 84/300 | 6030 | 1547 |
| **v1 Material** | ~2500–2600 | 54% vs 2400, 49.5% vs 2600 | 285/300 | 9631 | 2072 |
| **v1 Space** (default) | ~2750 | 86% vs 2400, 73.5% vs 2600, 42.5% vs 2800, 17% vs 3000 | 285/300 | 10875 | 2296 |
| **v1 Full** | ~2750 (+37 ± 16 vs Space) | 90% vs 2400, 76% vs 2600, 36.5% vs 2800, 14.5% vs 3000 | 283/300 | 10868 | 2258 |
| Stockfish 19 (full strength, reference) | 3804 in CCRL Blitz (8 threads) | | 279/300 | 13876 (0.1 s) | 3832 |

- Against Stockfish, Space and Full score the same within the margin of error (±60–90 Elo per level),
  but Full wins the direct match against Space by +37 ± 16 (1000 games at 8+0.08).
- WAC: [Win At Chess](tools/suites/wac.epd), 300 tactical positions. STS: [Strategic Test Suite](tools/suites/sts.epd),
  1500 positional positions, points out of 15000 with `tools/sts.py`; STS Elo is the CCRL 40/4 estimate of
  [`sts_rating.py`](https://github.com/fsmosca/STS-Rating) (v0: from its score with the same formula).
  STS Elo underrates Chessy: it measures single positional moves, while Chessy gains its points in the search.
- WAC is from 1958 and some of its solutions are not the only winning move: Stockfish misses the expected move
  in 21 positions, usually playing another winning move (sometimes a faster mate). WAC measures finding the expected move,
  so it is useful to compare versions of Chessy, not to compare with Stockfish.
- Greedy has no quiescence search, so it does not see the recapture of its last capture (horizon effect): depth 4 is barely
  better than depth 3. Its results are not monotonic: odd depths end on its own move and see more captures (better in WAC),
  even depths end on the reply of the opponent and are more careful (better in STS). Greedy searches a fixed depth,
  so the time per position does not change its moves.

v1.01 adds the opening book and the Syzygy tablebases. Against Stockfish with both (`OwnBook`, 3–5 pieces), from the
initial position so that the book plays the opening (v1.0: balanced openings), 100 games per level at 10+0.1:

| Against Stockfish | 2400 | 2600 | 2800 | 3000 | Elo (fit of the 4 levels) |
|---|---|---|---|---|---|
| Space v1.0 | 86% | 73.5% | 42.5% | 17% | 2746 ± 40 |
| Space v1.01 | 86% | 79% | 42.5% | 20.5% | 2768 ± 40 |
| Full v1.0 | 90% | 76% | 36.5% | 14.5% | 2741 ± 40 |
| Full v1.01 | 84% | 71% | 42.5% | 25.5% | 2756 ± 40 |

- v1.01 is +22 (Space) and +15 (Full) Elo, within the margin of error (±57 for a difference): no measurable change.
- Against limited Stockfish the book plays only ~2.5 moves per game: Stockfish varies its replies (93% of the games
  have different first 8 plies) and soon leaves the repertoire. The tablebases are reached in 20–45% of the games.

STS by theme (`tools/sts.py`, 0.2 s per position, points out of 1000; Stockfish with 0.1 s):

| Theme | v0 | Greedy | Material | Space | Full | Stockfish 19 |
|---|---|---|---|---|---|---|
| Undermining | 317 | 230 | 542 | **679** | 675 | 929 |
| Open files and diagonals | 344 | 251 | 553 | **681** | 680 | 912 |
| Knight outposts | 396 | 178 | 632 | 700 | **731** | 923 |
| Square vacancy | 312 | 197 | 607 | 650 | **686** | 941 |
| Bishop vs knight | 543 | **857** | 744 | 843 | 841 | 962 |
| Recapturing | 823 | 767 | 869 | 892 | **898** | 954 |
| Offer of simplification | 434 | 212 | 631 | 665 | **666** | 902 |
| Advancement of f/g/h pawns | 305 | 193 | 509 | **645** | 621 | 902 |
| Advancement of a/b/c pawns | 305 | 190 | 495 | **590** | 521 | 862 |
| Simplification | 652 | **865** | 726 | 826 | 793 | 967 |
| King activity | 389 | 276 | 601 | 624 | **693** | 909 |
| Center control | 436 | 264 | 672 | **774** | 764 | 923 |
| Pawn play in the center | 524 | 278 | 701 | 752 | **761** | 907 |
| 7th rank | 491 | 313 | 725 | **827** | 824 | 941 |
| Avoid pointless exchange | 464 | 485 | 624 | **727** | 714 | 942 |
| **Total** | 6735 | 5556 | 9631 | **10875** | 10868 | 13876 |

Space is better at pawn play (pawn advances on both wings, undermining) and center control; Full at king activity,
outposts and square vacancy (piece activity). Greedy only shines where the answer is a capture or an exchange
(bishop vs knight, simplification).

Style metrics (`tools/style.py`, after the opening, against Stockfish at a similar level: v0 and Greedy against
1320–1600, Material against 2600, Space and Full against 2600 and 2800):

| | Opponent mobility | Space | Territory | Opponent trapped pieces | Wins behind in material |
|---|---|---|---|---|---|
| **v0** | 27.9 | 0.79 | −1.08 | 0.27 | 10% |
| **v1 Greedy** | 38.9 | −0.13 | −6.42 | 0.32 | 2 of 7 wins |
| **v1 Material** | 28.9 | 0.52 | −2.13 | 0.15 | 2% |
| **v1 Space** | 25.5 | **1.00** | **0.74** | **0.21** | 3% |
| **v1 Full** | **25.3** | 0.61 | 0.16 | 0.19 | 2% |
| Stockfish 2600 (against Space) | 26.7 | −0.92 | −0.72 | 0.16 | |
| Stockfish 2800 (against Space) | 22.3 | −0.95 | 0.08 | 0.16 | |

- **v0**: an alpha-beta search to depth 3–4 with material, piece-square tables and mobility: it already gains some space,
  but it does not control the half of the opponent (negative territory).
- **Greedy**: grabs material without looking further than two plies: it is passive, gives away territory
  and lets the opponent play (the highest opponent mobility).
- **Material**: plays sound chess without a plan for space: the opponent keeps its mobility and territory.
- **Space**: the constrictor. It gains the most space and territory and traps the most pieces.
- **Full**: as restrictive as Space with the mobility of the opponent, but with less space and territory:
  it plays for activity and material more than for space.
- Against Stockfish 2800, Stockfish restricts Chessy more (Chessy keeps 22.3 moves, Stockfish 24.7),
  but Chessy still gains more space; against 2600 the restriction is even.

### Strength levels (UCI_Elo)

With `UCI_LimitStrength` and `UCI_Elo` (or `--elo` in `play`), Chessy plays at an Elo from 900 to 2800 in steps of 100.
The lowest levels play the greedy search, the middle levels the Space style (or the style of the `Style` option)
limited to a number of nodes per move, so it keeps every technique of its search (quiescence, extensions, reductions)
and only sees less, and the top level plays Full without limit. The Elo of the limited search grows with the logarithm
of the nodes: about **135 Elo per doubling** (50 games against Stockfish per point, 10+0.1):

| Nodes per move | 64 | 256 | 1024 | 4096 | 16384 | 65536 | 262144 |
|---|---|---|---|---|---|---|---|
| Against Stockfish | 49% vs 1320 | 26% vs 1500 | 23% vs 1800 | 35% vs 2100 | 52% vs 2300 | 51% vs 2500 | 76% vs 2600 |
| Elo | ~1310 | ~1320 | ~1590 | ~1990 | ~2310 | ~2510 | ~2800 |

| UCI_Elo | 900 | 1000 | 1100 | 1200 | 1300 | 1500 | 1800 | 2000 | 2200 | 2500 | 2700 | 2800 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Plays | Greedy depth 2 | Greedy depth 2 | Greedy depth 3 | Greedy depth 4 | Space, 127 nodes | 356 nodes | 1.7k nodes | 4.6k nodes | 13k nodes | 60k nodes | 169k nodes | Full |

The nodes are reached in a fraction of a second, so the levels play fast and do not depend on the time control.

### CCRL estimate

The `UCI_Elo` of Stockfish is fitted to games against versions of the Stash engine with known CCRL Blitz ratings,
at 120+1 (Stockfish source, `search.h`; the Stash anchors are within ±100 Elo of CCRL and the highest one is 2744),
so it is roughly a **CCRL Blitz** scale. At the time control of CCRL Blitz (2+1) Chessy does better than at 10+0.1:

| Against Stockfish 2800 | 10+0.1 (100 games) | 2+1 (40 games) |
|---|---|---|
| Space | 42.5% (−53 ± 60) | 61.3% (+80 ± 96) |
| Full | 36.5% (−96 ± 62) | 55% (+35 ± 105) |

Estimate: **~2800–2850 in CCRL Blitz** for Space and Full (the same within the margin of error), with these caveats:
few games at 2+1 (±70 Elo for both styles together), this machine is faster than the reference machine of CCRL
(Intel i7-4770K), which makes 2+1 here a longer time control, and above 2744 the scale of Stockfish is extrapolated.

- **CCRL Blitz** (2+1, ~890 engines): Space and Full would rank around 295–310, next to engines like Octochess r5506 (2803),
  Tornado 8.0 (2824) or Clovis III (2828). Material (~2600 at 2+1, if it gains as much) would be around rank 370,
  v0 (~1500) around rank 780 and Greedy (~900) near the bottom, around rank 870.
- **CCRL 40/2 FRC** (Chess960, 151 engines): the same engines are 20–50 Elo lower there than in Blitz, so Space and Full
  would be ~2770–2820, around rank 105–110 (Colossus 2026b 2795, Tornado 8.0 2805, Loop for Chess960 2749).
  Chessy plays Chess960 since v1.01 (`UCI_Chess960`), so it could enter this list.

How to get a real CCRL rating is described in the [PRD](docs/PRD.md) (§9, CCRL).

## Play against Chessy

### In the console

Chessy plays in the console with moves in SAN (`e4`, `Nf3`, `O-O`, `exd6`, `a8=Q`) or UCI (`e2e4`).
This is also the way to play on an electronic board in two-player mode: play your move on the board, type it,
and play the answer of Chessy on the board. Commands: `moves`, `eval` (evaluation of the position, term by term),
`undo`, `pgn`, `resign`, `draw`, `quit`.

```
cd engine
./gradlew installDist
build/install/chessy/bin/chessy play --style space --time 5 --pgn games/space.pgn
build/install/chessy/bin/chessy play --style full --time 5 --chessy white
build/install/chessy/bin/chessy play --style material --time 5
build/install/chessy/bin/chessy play --style greedy --depth 3
build/install/chessy/bin/chessy play --elo 1600
build/install/chessy/bin/chessy play --opening French --chessy white
build/install/chessy/bin/chessy play --chess960 random
```

Chessy plays black by default (`--chessy white`, `both` or `none`), thinks `--time` seconds per move
(and `--depth` limits the search; for greedy it is its fixed depth), and saves the game with `--pgn`
(at the end of the file if it already exists, so a file can collect all the games against Chessy).
It plays with its opening book (`--book off` to disable it, `--opening` to play one opening of the book) and, if they are
downloaded in `tools/syzygy`, with the endgame tablebases (`--syzygy off` to disable them, or `--syzygy <folder>`).
Both are shown when the game starts.

When it starts, Chessy shows its style and its estimated Elo, which also go to the PGN (`White`/`Black` and
`WhiteElo`/`BlackElo` tags, `-` for the player). After each move it shows its evaluation in pawns from the point of view
of White, like the analysis boards: `+2.40 (White is better)`, `-0.35 (Black is better)` or `mate in 3 for Black`.

Chessy v0 (branch `v0`, Maven) also plays in the console, white by default:

```
git worktree add tools/engines/v0 v0
cd tools/engines/v0
mvn -q compile exec:java -Dexec.args="--black --time 5 --pgn games/v0.pgn"
```

v0 has no UCI, but [`tools/v0_uci.py`](tools/v0_uci.py) adapts it to play in chess GUIs and fastchess
(see [tools](tools/README.md)).

### In a chess GUI

Without a command, `chessy` speaks UCI: add `engine/build/install/chessy/bin/chessy` as an engine in any UCI GUI
(Cute Chess, Arena, BanksiaGUI, En Croissant...) and choose the style in the engine options (`Style`), or an Elo
(`UCI_LimitStrength` and `UCI_Elo`). Set `OwnBook` to use its opening book (off by default in UCI, as the rating lists
require); the tablebases of `tools/syzygy` are used by default (`SyzygyPath`).

### On Lichess (and with an electronic board)

Chessy can play on Lichess as a BOT with [lichess-bot](tools/lichess) (`LICHESS_BOT_TOKEN=xxx tools/lichess/run.sh`,
or on a server with Docker, so it stays online when your computer is off):
challenge it from your account,
from the web or from an electronic board connected to Lichess (the ChessUp 2 connects to Lichess directly,
and [chessconnect](https://chessconnect.de/) connects many boards to Lichess). A bot plays one configuration,
so the bot plays the default style, Space, with its opening book and the tablebases (set `UCI_LimitStrength` and
`UCI_Elo` in its configuration for a weaker bot). ChessConnect only connects boards to online sites, not to local engines,
so playing on the board against a local Chessy needs the Lichess bot.

Games are saved in PGN, so they can be opened with the viewer (`python tools/game.py games/last.pgn`) or analyzed in Lichess or Chess.com.

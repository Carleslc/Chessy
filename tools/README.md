# Chessy tools

Python tooling around the engine: board and game viewer, matches between UCI engines, testing helpers and the [lichess-bot](lichess) configuration.

## Install

```
pip install --upgrade -r requirements.txt
```

## Board

Display a board given its FEN notation.

```
python board.py --fen "7r/3B4/8/p5pp/4Qbk1/1P4B1/P4PKP/8 b - - 1 39" -c
```

```
usage: board.py [-h] [--fen FEN] [--coordinates] [--black]

options:
  -h, --help         show this help message and exit
  --fen FEN, -f FEN  board representation with fen notation
  --coordinates, -c  show coordinates
  --black, -b        set the point of view to black
```

## Game

Display a game given its PGN notation.

```
python game.py examples/001.pgn
```

```
usage: game.py [-h] [--end] [--coordinates] [--black] [--match MATCH] pgn

positional arguments:
  pgn                   game file with PGN notation

options:
  -h, --help            show this help message and exit
  --end, -e             skips to the last move
  --coordinates, -c     show coordinates
  --black, -b           set the point of view to black
  --match MATCH, -m MATCH match number
```

## Match

Play games between two UCI engines (Chessy against itself by default), checking that every move is legal, the protocol works and no engine runs out of time.
Openings start with random plies for variety and colors alternate. Games are saved in PGN (open them with `game.py`).

```
python match.py --games 100 --tc 10+0.1
python match.py --games 20 --tc 60+1 --engine2 stockfish --option2 UCI_LimitStrength=true --option2 UCI_Elo=1350
python game.py games/match.pgn --match 3
```

```
options:
  --games, -n         number of games (colors alternate)
  --engine1, -e1      first engine command (default: Chessy, build it with ./gradlew installDist)
  --engine2, -e2      second engine command (default: same as the first)
  --option1, -o1      UCI option NAME=VALUE for the first engine (repeatable)
  --option2, -o2      UCI option NAME=VALUE for the second engine (repeatable)
  --tc                time control in seconds: base+increment (e.g. 10+0.1)
  --movetime          seconds per move when there is no time control (default: 0.05)
  --random-plies      random plies at the start of each game (default: 8)
  --concurrency, -c   games played at the same time (default: 4)
  --seed              random seed for the openings
  --pgn               file to save the games (default: games/match.pgn)
```

## Engine matches with fastchess

[fastchess](https://github.com/Disservin/fastchess) plays engine matches and runs SPRT tests. It is built from source:

```
git clone https://github.com/Disservin/fastchess.git ~/.local/src/fastchess
make -C ~/.local/src/fastchess -j8
ln -s ~/.local/src/fastchess/fastchess ~/.local/bin/fastchess
```

Chessy (alpha-beta search) against Chessy with the greedy search, and against Stockfish with a limited strength:

```
CHESSY=../engine/build/install/chessy/bin/chessy
fastchess -engine cmd=$CHESSY name=AlphaBeta -engine cmd=$CHESSY name=Greedy2 option.Search=Greedy "option.Greedy Depth=2" \
  -each tc=10+0.1 -openings file=openings/balanced.epd format=epd order=random -rounds 50 -repeat -concurrency 8 -pgnout file=games/greedy.pgn
fastchess -engine cmd=$CHESSY name=Chessy -engine cmd=stockfish name=SF1500 option.UCI_LimitStrength=true option.UCI_Elo=1500 \
  -each tc=10+0.1 -openings file=openings/balanced.epd format=epd order=random -rounds 50 -repeat -concurrency 8 -pgnout file=games/sf1500.pgn
```

`-repeat` plays each opening twice with the colors swapped.

## SPRT tests

Each change of the engine is accepted only if it is stronger, with a Sequential Probability Ratio Test (SPRT),
which plays games until it can decide with the given confidence. Save a copy of each version with `snapshot.sh`
(so the engine can keep changing while the test runs), then compare two versions with `sprt.sh`:

```
./snapshot.sh base        # engines/base
# ... change the engine ...
./snapshot.sh new         # engines/new
./sprt.sh new base        # elo0=0 elo1=10, tc=8+0.08, 10 games at a time
./sprt.sh new base 0 5 10+0.1 8
ROUNDS=750 ./sprt.sh new base   # at most 1500 games
# Two styles of the same version
NEW_NAME=full NEW_ARGS=option.Style=Full BASE_NAME=space BASE_ARGS=option.Style=Space ./sprt.sh new new
```

`engines/` (ignored by git) keeps one snapshot per phase of the [PRD](../docs/PRD.md) to compare versions:
`phase3` (first alpha-beta, Material), `phase4` (advanced search, Material), `phase5` (Space evaluation)
and `phase6` (tuned, styles Space and Full), and `v0` (a worktree of the `v0` branch, see below).
Snapshots of a single test are removed when the test ends.

## Chessy v0

The first implementation (branch `v0`) only plays in the console. `v0_uci.py` adapts it to UCI, to play it in fastchess
or in a chess GUI (it starts v0 from the position of each move, so it pays the start of the JVM on every move:
use time controls of at least 1 minute):

```
git worktree add engines/v0 v0
(cd engines/v0 && mvn -q compile dependency:build-classpath -Dmdep.outputFile=classpath.txt)
fastchess -engine cmd=python3 args=v0_uci.py name=Chessy-v0 -engine cmd=engines/phase6/bin/chessy name=Greedy option.Style=Greedy \
  -each tc=60+0.6 -openings file=openings/balanced.epd format=epd -repeat -rounds 30 -concurrency 6 -pgnout file=games/v0.pgn
python epd.py suites/wac.epd --movetime 1.6 -e "python3 v0_uci.py"
```

## Openings

Generate an opening book of balanced positions (random plies evaluated by Stockfish) for engine matches:

```
python openings.py --count 500 --output openings/balanced.epd
```

## Test suites

Create a suite of tactical positions from PGN games, with Stockfish as the oracle (positions with a single good move),
and run an engine on any EPD suite with best moves (`bm`) or moves to avoid (`am`), such as Win At Chess (WAC) or the Strategic Test Suite (STS):

```
python tactics.py games/*.pgn --count 100 --output suites/tactics.epd
python epd.py suites/tactics.epd --movetime 1
python epd.py suites/wac.epd --movetime 1
```

Suites:
- `suites/tactics.epd`: 100 tactical positions from games of Chessy, created with `tactics.py`.
- `suites/wac.epd`: Win At Chess, 300 tactical positions by Fred Reinfeld (1958), from [viridithas](https://github.com/cosmobobak/viridithas/blob/master/assets/epds/wac.epd).

### Strategic Test Suite (STS)

`suites/sts.epd`: 1500 positional positions in 15 themes (undermining, open files, outposts, center control, king activity...),
by Dann Corbit and Swaminathan Natarajan, with the points of each move recalculated with Stockfish 15
(`STS1-STS15_LAN_v4.epd` from [STS-Rating](https://github.com/fsmosca/STS-Rating), MIT license).
Every position gives up to 10 points (the best move) and partial points to the other good moves.
`sts.py` shows the points of each theme:

```
python sts.py --movetime 0.2
python sts.py --movetime 0.2 --option Style=Material
python sts.py --engine stockfish --movetime 0.1
```

The suite also works with `sts_rating.py` of [STS-Rating](https://github.com/fsmosca/STS-Rating), which estimates a CCRL 40/4 rating
(`--getrating`). It expects `c9` to be the last field of each line and cannot set UCI options, so remove the `Ae` field first
and use a wrapper script for other styles:

```
sed 's/ Ae "[^"]*";$//' suites/sts.epd > games/sts-rating.epd
python sts_rating.py -f games/sts-rating.epd -e ../engine/build/install/chessy/bin/chessy -h 64 --getrating
```

## Style

Style metrics of the players of PGN games, to check that the "constrictor" style restricts the opponent:
opponent mobility, space, territory, opponent trapped pieces, pieces left at move 30 and wins behind in material.

```
python style.py games/space-vs-sf.pgn games/material-vs-sf.pgn
```

## Analysis

Plot the terms of the evaluation of Chessy (space, territory, restriction, mobility, material...) along a game:

```
python analysis.py games/match.pgn --match 3 --output games/analysis.png
python analysis.py games/match.pgn --terms Space Territory Restriction Mobility --style Space
```

## Opening book

`book.py` builds the Polyglot opening books of Chessy (`engine/src/main/resources/chessy/book/`) from the repertoire of
[`openings/book.txt`](openings/book.txt): one line per variation (opening, weight, moves in SAN, at most 12 moves per side).
The openings are those where one side gains space and the other prepares a freeing break (King's Indian, Benoni,
French, Caro-Kann and Sicilian with an early e5, closed Queen's Pawn, closed Ruy Lopez, Pirc and Modern).
It writes a book with every line (`book.bin`) and one per opening (UCI option `Opening`).
`--check` analyzes every move of the lines with Stockfish and reports those that lose too much:

```
python book.py --check --depth 16
```

## Syzygy tablebases

Chessy probes the [Syzygy tablebases](https://syzygy-tables.info/), by default the ones of `tools/syzygy` if they are downloaded
(UCI option `SyzygyPath`, `--syzygy` in the command line).
The tables of up to 5 pieces take 939 MiB (378 MiB of WDL tables, used in the search, and 561 MiB of DTZ tables,
used at the root). Download them into `tools/syzygy` (ignored by git):

```
mkdir -p syzygy && cd syzygy
curl -sL "https://syzygy-tables.info/download.txt?source=lichess&max-pieces=5" | xargs -P 4 -n 1 curl -sSfLO --retry 3
```

`syzygy_check.py` compares the probing of the engine (WDL and DTZ) with python-chess on random positions
of every table (20000 positions, all equal):

```
python syzygy_check.py --positions 5000
```

## Texel tuning

Tune the weights of the Space evaluation with the results of games ([Texel's tuning method](https://www.chessprogramming.org/Texel%27s_Tuning_Method)).
`texel.py` extracts the quiet positions of PGN games (not in check, the move played is not a capture, a promotion or a check)
with the result of their game, and `chessy tune` minimizes the error between the evaluation and the results,
saving the weights after every pass:

```
python openings.py --count 5000 --max-score 150 --depth 10 --seed 2 --output games/texel-openings.epd
fastchess -engine cmd=engines/phase5/bin/chessy name=a -engine cmd=engines/phase5/bin/chessy name=b -each tc=2+0.02 \
  -openings file=games/texel-openings.epd format=epd -repeat -rounds 5000 -concurrency 10 -pgnout file=games/texel-selfplay.pgn
python texel.py games/texel-selfplay.pgn --output games/texel.txt
../engine/build/install/chessy/bin/chessy tune games/texel.txt --output games/space.weights
```

The weights of the style (space, territory, restriction and mobility) are not tuned unless `--all true` is given.
Test a weights file with the UCI option `Weights` (for example `option.Weights=/path/space.weights` in fastchess)
before copying it to `engine/src/main/resources/chessy/eval/space.weights`, the default weights of the engine.

## Lichess

See [lichess/README.md](lichess/README.md) to run Chessy as a Lichess BOT with lichess-bot.

## Perft check

Compare the perft divide of the engine with python-chess, to find move generation bugs.
With `--san`, also compare the standard algebraic notation (SAN) of the legal moves.
Build the engine first with `./gradlew installDist` in `engine/`.

```
python perft_check.py 3 --fen "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
python perft_check.py 3 --random 100 --san
```

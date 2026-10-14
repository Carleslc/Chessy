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

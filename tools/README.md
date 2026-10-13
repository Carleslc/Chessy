# Chessy tools

Python tooling around the engine: board and game viewer, and (in later phases) game analysis scripts, testing helpers and the [lichess-bot](https://github.com/lichess-bot-devs/lichess-bot) bridge.

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

## Perft check

Compare the perft divide of the engine with python-chess, to find move generation bugs.
With `--san`, also compare the standard algebraic notation (SAN) of the legal moves.
Build the engine first with `./gradlew installDist` in `engine/`.

```
python perft_check.py 3 --fen "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
python perft_check.py 3 --random 100 --san
```

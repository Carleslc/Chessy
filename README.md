# Chessy
Artificial Intelligence playing Chess with Minimax Alpha-Beta prunning algorithm.

This project is not designed for professional usage, this is only for personal learning. For professional purposes use advanced chess engines like Stockfish.

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

## Future work ideas

Material values in Cp (Centipawns = 1/100 P)
P = 100
N = 320
B = 330
R = 500
Q = 900
K = 400 or 11000

Alternative piece values may be their square control * 100 (P)
Also the material value multiplied by their square control factor (current/maximum)
Maximum square control:
P = 2
N = 8
B = 13
R = 14
Q = 27
K = 8

- UCI Engine CLI
- Evaluation:
  - [Optional if K = 11000] Checkmate (Inf ∞)
  - Attack Maps (∑ attacked pieces * attacked piece value)
  - [Optional] Defense Maps (∑ defended pieces [except king] * defended piece value * 0.5)
  - Square Control (attacking squares * 20)
  - Mobility (legal moves * 10)
- Minimax + Alpha-Beta prunning
- Move ordering (MVV-LVA + Promotions)
- Zobrist Hashing (Transposition Tables)
- Iterative Deepening + Move ordering (scores from depth - 1)
- Quiescence Search (avoid horizon effect in depth + 1)
- MTD(f) with Null Window (prune non-improvement scores)
- ABDADA Parallel Search

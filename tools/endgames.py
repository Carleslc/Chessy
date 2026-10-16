#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Balanced endgame positions to start engine matches, to test the endgame play: positions of a training file of
texel.py (or EPD/FEN lines) with few pieces (material of pieces without pawns of 13 or less, as rook and minor
against rook and minor), some pawns, not in check, and balanced according to Stockfish.
Each kind of endgame (pawns, rooks, minor pieces, rook and minor, queens) gets its share of the positions.

python endgames.py games/texel2.txt --count 200 --output openings/endgames.epd
fastchess ... -openings file=openings/endgames.epd format=epd order=random -repeat
'''

import argparse
import random
from collections import defaultdict

import chess
import chess.engine

PIECE_VALUES = {chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9}

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("input", help="file with a FEN or EPD at the start of each line (texel.py training file)")
  parser.add_argument("--count", "-n", help="number of positions (default: 200)", type=int, default=200)
  parser.add_argument("--output", "-o", help="EPD file (default: openings/endgames.epd)", default="openings/endgames.epd")
  parser.add_argument("--max-score", help="maximum score of Stockfish, in centipawns (default: 60)", type=int, default=60)
  parser.add_argument("--depth", help="depth of Stockfish (default: 14)", type=int, default=14)
  parser.add_argument("--stockfish", help="Stockfish command", default="stockfish")
  parser.add_argument("--seed", type=int, default=1)
  args = parser.parse_args()

def kind(board: chess.Board) -> str | None:
  '''Kind of endgame, or None if it is not an endgame to test.'''
  count = lambda piece: len(board.pieces(piece, chess.WHITE)) + len(board.pieces(piece, chess.BLACK))
  material = sum(value * count(piece) for piece, value in PIECE_VALUES.items())
  pawns = count(chess.PAWN)
  if material > 26 or material < 6 and pawns < 4 or pawns < 3 or board.is_check():
    return None
  if count(chess.QUEEN):
    return 'queens'
  minors = count(chess.KNIGHT) + count(chess.BISHOP)
  if count(chess.ROOK):
    return 'rook and minor' if minors else 'rooks'
  return 'minor pieces' if minors else 'pawns'

if __name__ == "__main__":
  set_args()
  rng = random.Random(args.seed)
  candidates = defaultdict(list)
  with open(args.input) as lines:
    for line in lines:
      fen = line.split(';')[0].strip()
      fen = ' '.join(fen.split()[:4]) + ' 0 1' if len(fen.split()) >= 4 else fen
      board = chess.Board(fen)
      if (endgame := kind(board)):
        candidates[endgame].append(board.fen())
  kinds = sorted(candidates)
  print({name: len(fens) for name, fens in candidates.items()})

  stockfish = chess.engine.SimpleEngine.popen_uci(args.stockfish)
  selected = []
  for name in kinds:
    rng.shuffle(candidates[name])
  # Round robin over the kinds of endgame, keeping the balanced positions
  while len(selected) < args.count and any(candidates.values()):
    for name in kinds:
      if candidates[name] and len(selected) < args.count:
        board = chess.Board(candidates[name].pop())
        score = stockfish.analyse(board, chess.engine.Limit(depth=args.depth))['score'].white().score(mate_score=10000)
        if abs(score) <= args.max_score:
          selected.append(board.epd(c0=f'{name}, Stockfish {score:+}'))
  stockfish.quit()
  with open(args.output, 'w') as output:
    output.write('\n'.join(selected) + '\n')
  print(f'{len(selected)} positions saved in {args.output}')

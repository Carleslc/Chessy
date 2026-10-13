#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Generate an opening book of balanced positions for engine matches (fastchess, cutechess).
Random plies are played from the starting position and only positions that Stockfish considers balanced are kept.

python openings.py --count 500 --output openings/balanced.epd
'''

import argparse
import random
from pathlib import Path

import chess
import chess.engine

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("--count", "-n", help="number of positions", type=int, default=500)
  parser.add_argument("--min-plies", help="minimum random plies", type=int, default=6)
  parser.add_argument("--max-plies", help="maximum random plies", type=int, default=10)
  parser.add_argument("--max-score", help="maximum absolute score in centipawns to be balanced", type=int, default=50)
  parser.add_argument("--depth", help="Stockfish depth to evaluate the positions", type=int, default=12)
  parser.add_argument("--stockfish", help="Stockfish command", default="stockfish")
  parser.add_argument("--seed", help="random seed", type=int, default=1)
  parser.add_argument("--output", "-o", help="EPD file", default="openings/balanced.epd")
  args = parser.parse_args()

if __name__ == "__main__":
  set_args()

  rng = random.Random(args.seed)
  positions = {}
  tries = 0

  with chess.engine.SimpleEngine.popen_uci(args.stockfish) as stockfish:
    while len(positions) < args.count:
      tries += 1
      board = chess.Board()
      for _ in range(rng.randint(args.min_plies, args.max_plies)):
        board.push(rng.choice(list(board.legal_moves)))
      if board.is_game_over() or board.epd() in positions:
        continue
      score = stockfish.analyse(board, chess.engine.Limit(depth=args.depth))['score'].white()
      if not score.is_mate() and abs(score.score()) <= args.max_score:
        positions[board.epd()] = score.score()
        if len(positions) % 50 == 0:
          print(f'{len(positions)}/{args.count} positions ({tries} tries)', flush=True)

  output = Path(args.output)
  output.parent.mkdir(parents=True, exist_ok=True)
  output.write_text(''.join(f'{epd}\n' for epd in positions))
  print(f'Saved {len(positions)} positions to {output}')

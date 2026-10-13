#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Create a test suite (EPD) of tactical positions from PGN games, using Stockfish as the oracle:
positions with a single good move are kept, with that move as best move (bm). The best move must be much better
than the second best, and the second best must not be winning (otherwise there are several good moves).

python tactics.py games/*.pgn --count 100 --output suites/tactics.epd
'''

import argparse
import random
from pathlib import Path

import chess
import chess.engine
import chess.pgn

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("pgn", help="PGN files with the games", nargs='+')
  parser.add_argument("--count", "-n", help="number of positions", type=int, default=100)
  parser.add_argument("--margin", help="minimum difference in centipawns between the best and the second best move", type=int, default=200)
  parser.add_argument("--max-second", help="maximum score in centipawns of the second best move", type=int, default=100)
  parser.add_argument("--min-ply", help="skip the first plies of each game (opening)", type=int, default=16)
  parser.add_argument("--depth", help="Stockfish depth", type=int, default=16)
  parser.add_argument("--stockfish", help="Stockfish command", default="stockfish")
  parser.add_argument("--seed", help="random seed to choose the positions", type=int, default=1)
  parser.add_argument("--output", "-o", help="EPD file", default="suites/tactics.epd")
  args = parser.parse_args()

def positions(paths: list[str]):
  for path in paths:
    with open(path) as pgn:
      while game := chess.pgn.read_game(pgn):
        board = game.board()
        for ply, move in enumerate(game.mainline_moves()):
          if ply >= args.min_ply:
            yield board.copy(stack=False)
          board.push(move)

def score(info) -> int:
  return info['score'].relative.score(mate_score=100_000)

if __name__ == "__main__":
  set_args()

  candidates = list({board.epd(): board for board in positions(args.pgn)}.values())
  random.Random(args.seed).shuffle(candidates)
  suite = []

  with chess.engine.SimpleEngine.popen_uci(args.stockfish) as stockfish:
    for board in candidates:
      if len(suite) >= args.count:
        break
      if board.legal_moves.count() < 2:
        continue
      infos = stockfish.analyse(board, chess.engine.Limit(depth=args.depth), multipv=2)
      best, second = score(infos[0]), score(infos[1])
      # A single good move: much better than the alternatives, which are not winning, and not already lost
      if best - second >= args.margin and second <= args.max_second and best >= 0:
        move = infos[0]['pv'][0]
        suite.append(board.epd(bm=move, id=f'tactics.{len(suite) + 1:03d}', c0=f'stockfish {infos[0]["score"].relative} second {infos[1]["score"].relative}'))
        if len(suite) % 10 == 0:
          print(f'{len(suite)}/{args.count} positions', flush=True)

  output = Path(args.output)
  output.parent.mkdir(parents=True, exist_ok=True)
  output.write_text(''.join(f'{line}\n' for line in suite))
  print(f'Saved {len(suite)} positions to {output}')

#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Create a training file for the Texel tuning of Snaky (snaky tune) from PGN games:
one quiet position per line with the result of its game for white, "<fen>;<result>" (1, 0.5 or 0).

A position is quiet when the side to move is not in check and the move played in the game is not a capture,
a promotion or a check: the static evaluation can judge it without searching tactics.
Repeated positions are kept once. Games without a result are skipped.

python texel.py games/texel-selfplay.pgn --output games/texel.txt
cd ../engine && build/install/snaky/bin/snaky tune ../tools/games/texel.txt --output src/main/resources/snaky/eval/space.weights
'''

import argparse
import random
from pathlib import Path

import chess
import chess.pgn

RESULTS = {'1-0': '1', '0-1': '0', '1/2-1/2': '0.5'}

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("pgn", help="PGN files with the games", nargs='+')
  parser.add_argument("--output", "-o", help="training file", default="games/texel.txt")
  parser.add_argument("--min-ply", help="skip the first plies of each game", type=int, default=4)
  parser.add_argument("--per-game", help="maximum positions of each game (default: all)", type=int)
  parser.add_argument("--seed", help="random seed to choose the positions of each game and shuffle them", type=int, default=1)
  args = parser.parse_args()

def quiet_positions(game: chess.pgn.Game):
  board = game.board()
  for ply, move in enumerate(game.mainline_moves()):
    if ply >= args.min_ply and not board.is_check() and not board.is_capture(move) and not move.promotion and not board.gives_check(move):
      yield board.fen()
    board.push(move)

if __name__ == "__main__":
  set_args()

  rng = random.Random(args.seed)
  positions = {}
  games = 0
  for path in args.pgn:
    with open(path) as pgn:
      while game := chess.pgn.read_game(pgn):
        result = RESULTS.get(game.headers.get('Result'))
        if result is None:
          continue
        games += 1
        fens = list(quiet_positions(game))
        if args.per_game and len(fens) > args.per_game:
          fens = rng.sample(fens, args.per_game)
        for fen in fens:
          # Same position with other move counters: keep the first one
          positions.setdefault(' '.join(fen.split()[:4]), f'{fen};{result}')
        if games % 1000 == 0:
          print(f'{games} games, {len(positions)} positions', flush=True)

  lines = list(positions.values())
  rng.shuffle(lines)
  output = Path(args.output)
  output.parent.mkdir(parents=True, exist_ok=True)
  output.write_text(''.join(f'{line}\n' for line in lines))
  print(f'Saved {len(lines)} positions of {games} games to {output}')

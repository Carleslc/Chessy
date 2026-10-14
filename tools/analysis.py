#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Analyze a game with the evaluation of Chessy: for each position, the terms of the evaluation
(space, territory, restriction, mobility, material...) from the point of view of white, plotted along the game.

python analysis.py games/match.pgn --match 3 --output games/analysis.png
python analysis.py games/match.pgn --terms Space Territory Restriction Mobility --style Space
'''

import argparse
import re
import subprocess
from pathlib import Path

import chess.pgn

DEFAULT_ENGINE = Path(__file__).resolve().parent.parent / 'engine' / 'build' / 'install' / 'chessy' / 'bin' / 'chessy'
DEFAULT_TERMS = ['Material', 'Mobility', 'Space', 'Territory', 'Restriction', 'King safety']

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("pgn", help="game file with PGN notation")
  parser.add_argument("--match", "-m", help="game number in the file", default=1, type=int)
  parser.add_argument("--terms", "-t", help="evaluation terms to plot", nargs='+', default=DEFAULT_TERMS)
  parser.add_argument("--style", "-s", help="evaluation style of Chessy", default="Space")
  parser.add_argument("--engine", "-e", help="Chessy command (build it with ./gradlew installDist)", default=str(DEFAULT_ENGINE))
  parser.add_argument("--output", "-o", help="image file (default: show the plot)")
  args = parser.parse_args()

class Evaluator:
  '''Chessy in UCI mode, asked with the debug command "eval".'''

  TERM = re.compile(r'^(.+?)\s+\(-?\d+, -?\d+\)\s+\(-?\d+, -?\d+\)\s+\(-?\d+, -?\d+\) = (-?\d+)$')

  def __init__(self, command: str, style: str):
    self.process = subprocess.Popen([command], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    self.send(f'setoption name Style value {style}')

  def send(self, command: str):
    self.process.stdin.write(command + '\n')
    self.process.stdin.flush()

  def terms(self, fen: str) -> dict[str, int]:
    self.send(f'position fen {fen}')
    self.send('eval')
    terms = {}
    while True:
      line = self.process.stdout.readline()
      if not line:
        raise RuntimeError('The engine stopped')
      line = line.rstrip()
      if line.startswith('Total:'):
        terms['Total'] = int(line.split()[1])
        return terms
      match = self.TERM.match(line)
      if match:
        terms[match.group(1).strip()] = int(match.group(2))

  def quit(self):
    self.send('quit')
    self.process.wait()

def read_game(path: str, number: int) -> chess.pgn.Game:
  with open(path) as pgn:
    for _ in range(number - 1):
      chess.pgn.skip_game(pgn)
    game = chess.pgn.read_game(pgn)
  if game is None:
    raise SystemExit(f'Game #{number} not found')
  return game

if __name__ == "__main__":
  set_args()

  import matplotlib.pyplot as plt

  game = read_game(args.pgn, args.match)
  evaluator = Evaluator(args.engine, args.style)
  board = game.board()
  plies, values = [], {term: [] for term in args.terms + ['Total']}
  try:
    for ply, move in enumerate([None] + list(game.mainline_moves())):
      if move:
        board.push(move)
      if board.is_game_over():
        break
      terms = evaluator.terms(board.fen())
      plies.append(ply)
      for term in values:
        values[term].append(terms.get(term, 0))
  finally:
    evaluator.quit()

  white, black = game.headers.get('White', 'White'), game.headers.get('Black', 'Black')
  figure, axes = plt.subplots(figsize=(12, 6))
  for term, series in values.items():
    axes.plot(plies, series, label=term, linewidth=2.5 if term == 'Total' else 1.5)
  axes.axhline(0, color='gray', linewidth=0.8)
  axes.set_xlabel('Ply')
  axes.set_ylabel(f'Centipawns (positive: {white})')
  axes.set_title(f'{white} - {black} ({game.headers.get("Result", "*")}), {args.style} evaluation')
  axes.legend()
  figure.tight_layout()
  if args.output:
    figure.savefig(args.output, dpi=120)
    print(f'Saved {args.output}')
  else:
    plt.show()

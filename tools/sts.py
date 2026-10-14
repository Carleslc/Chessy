#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Run a UCI engine on the Strategic Test Suite (STS): 15 themes of 100 positional positions each.
Every position gives points to the best 10 moves (c8: points, c9: moves in UCI), 10 points for the best one,
so the maximum is 1000 points per theme and 15000 in total.

The suite (suites/sts.epd) is STS1-STS15_LAN_v4.epd from https://github.com/fsmosca/STS-Rating (MIT license),
with the points of each position calculated with Stockfish 15.

python sts.py --movetime 0.2
python sts.py --movetime 0.2 --option Style=Material
python sts.py --engine stockfish --movetime 0.1
'''

import argparse
import re
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import chess
import chess.engine

TOOLS = Path(__file__).resolve().parent
DEFAULT_ENGINE = TOOLS.parent / 'engine' / 'build' / 'install' / 'chessy' / 'bin' / 'chessy'
THEME = re.compile(r'STS\(v[\d.]+\) (.+)\.\d+')
# Themes with abbreviated names in the ids of the suite (titles of sts_rating.py)
THEME_NAMES = {'AKPC': 'Advancement of f/g/h pawns', 'AT': 'Avoid pointless exchange'}

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("--epd", help="STS file", default=str(TOOLS / 'suites' / 'sts.epd'))
  parser.add_argument("--engine", "-e", help="engine command (default: Chessy, build it with ./gradlew installDist)", default=str(DEFAULT_ENGINE))
  parser.add_argument("--option", "-o", help="UCI option NAME=VALUE (repeatable)", action='append', default=[])
  parser.add_argument("--movetime", "-t", help="seconds per position", type=float, default=0.2)
  parser.add_argument("--concurrency", "-c", help="engines running at the same time", type=int, default=4)
  parser.add_argument("--themes", help="only the themes containing any of these words", nargs='+')
  args = parser.parse_args()

def read_suite(path: str) -> list[tuple[str, chess.Board, dict[chess.Move, int]]]:
  suite = []
  for line in Path(path).read_text().splitlines():
    if not line.strip():
      continue
    board, operations = chess.Board.from_epd(line)
    theme = THEME.match(operations['id']).group(1)
    theme = THEME_NAMES.get(theme, theme)
    if args.themes and not any(word.lower() in theme.lower() for word in args.themes):
      continue
    points = dict(zip((chess.Move.from_uci(move) for move in operations['c9'].split()), map(int, operations['c8'].split())))
    suite.append((theme, board, points))
  return suite

def solve(positions: list[tuple[str, chess.Board, dict[chess.Move, int]]]) -> list[tuple[str, int]]:
  results = []
  with chess.engine.SimpleEngine.popen_uci(args.engine.split()) as engine:
    if args.option:
      engine.configure(dict(option.split('=', 1) for option in args.option))
    for number, (theme, board, points) in enumerate(positions):
      move = engine.play(board, chess.engine.Limit(time=args.movetime), game=number).move
      results.append((theme, points.get(move, 0)))
  return results

if __name__ == "__main__":
  set_args()

  suite = read_suite(args.epd)
  chunks = [suite[i::args.concurrency] for i in range(args.concurrency)]
  scores, counts = defaultdict(int), defaultdict(int)
  with ThreadPoolExecutor(args.concurrency) as executor:
    for results in executor.map(solve, chunks):
      for theme, points in results:
        scores[theme] += points
        counts[theme] += 1

  themes = sorted(scores, key=lambda theme: next(i for i, (t, _, _) in enumerate(suite) if t == theme))
  for theme in themes:
    print(f'{theme:46} {scores[theme]:5} / {counts[theme] * 10} ({scores[theme] / (counts[theme] * 10):.0%})')
  total, maximum = sum(scores.values()), sum(counts.values()) * 10
  print(f'{"Total":46} {total:5} / {maximum} ({total / maximum:.1%}) with {args.movetime} s per position')

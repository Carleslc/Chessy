#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Run a UCI engine on a test suite in EPD format (with best moves "bm" or moves to avoid "am"),
such as the suites created with tactics.py, Win At Chess (WAC) or the Strategic Test Suite (STS).

python epd.py suites/tactics.epd --movetime 1
python epd.py suites/tactics.epd --engine stockfish --movetime 0.1
'''

import argparse
from pathlib import Path

import chess
import chess.engine

DEFAULT_ENGINE = Path(__file__).resolve().parent.parent / 'engine' / 'build' / 'install' / 'chessy' / 'bin' / 'chessy'

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("epd", help="EPD file")
  parser.add_argument("--engine", "-e", help="engine command (default: Chessy, build it with ./gradlew installDist)", default=str(DEFAULT_ENGINE))
  parser.add_argument("--option", "-o", help="UCI option NAME=VALUE (repeatable)", action='append', default=[])
  parser.add_argument("--movetime", "-t", help="seconds per position", type=float, default=1)
  parser.add_argument("--depth", "-d", help="fixed depth instead of time", type=int)
  parser.add_argument("--verbose", "-v", help="show every position", action='store_true')
  args = parser.parse_args()

if __name__ == "__main__":
  set_args()

  lines = [line for line in Path(args.epd).read_text().splitlines() if line.strip()]
  limit = chess.engine.Limit(depth=args.depth) if args.depth else chess.engine.Limit(time=args.movetime)
  solved = 0
  failed = []

  with chess.engine.SimpleEngine.popen_uci(args.engine.split()) as engine:
    if args.option:
      engine.configure(dict(option.split('=', 1) for option in args.option))
    for number, line in enumerate(lines, 1):
      board, operations = chess.Board.from_epd(line)
      move = engine.play(board, limit, game=number).move
      best = operations.get('bm', [])
      avoid = operations.get('am', [])
      ok = (not best or move in best) and move not in avoid
      solved += ok
      name = operations.get('id', number)
      if not ok:
        expected = ' '.join(board.san(m) for m in best) if best else f'not {" ".join(board.san(m) for m in avoid)}'
        failed.append(f'{name}: played {board.san(move)}, expected {expected}')
      if args.verbose:
        print(f'{"OK  " if ok else "FAIL"} {name}: {board.san(move)}', flush=True)

  print(f'Solved {solved}/{len(lines)} ({solved / len(lines):.0%}) with {limit}')
  for failure in failed[:30]:
    print(f'  {failure}')

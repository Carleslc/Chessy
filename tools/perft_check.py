#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Compare the perft divide of the engine with python-chess, to find move generation bugs.
With --san, also compare the standard algebraic notation of the legal moves.

python perft_check.py 3 --fen "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
python perft_check.py 3 --random 100 --san
python perft_check.py 3 --random 100 --san --chess960
'''

import argparse
import random
import subprocess
from pathlib import Path

from chess import Board, STARTING_FEN

DEFAULT_ENGINE = Path(__file__).resolve().parent.parent / 'engine' / 'build' / 'install' / 'snaky' / 'bin' / 'snaky'

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("depth", help="perft depth", type=int)
  parser.add_argument("--fen", "-f", help="position to check", default=STARTING_FEN)
  parser.add_argument("--random", "-r", help="check this number of random positions instead", type=int, default=0)
  parser.add_argument("--san", "-s", help="also compare the SAN of the legal moves", action='store_true')
  parser.add_argument("--chess960", help="Chess960: random positions from random starting positions, castling as king takes rook", action='store_true')
  parser.add_argument("--engine", "-e", help="engine executable (build it with ./gradlew installDist)", default=str(DEFAULT_ENGINE))
  args = parser.parse_args()

def perft(board: Board, depth: int) -> int:
  if depth == 0:
    return 1
  nodes = 0
  for move in board.legal_moves:
    board.push(move)
    nodes += perft(board, depth - 1)
    board.pop()
  return nodes

def board(fen: str) -> Board:
  return Board(fen, chess960=args.chess960)

def expected_divide(fen: str, depth: int) -> dict[str, int]:
  board = globals()['board'](fen)
  divide = {}
  for move in board.legal_moves:
    board.push(move)
    divide[move.uci()] = perft(board, depth - 1)
    board.pop()
  return divide

def engine_divide(engine: str, fen: str, depth: int) -> dict[str, int]:
  output = subprocess.run([engine, 'divide', str(depth), fen, '--chess960', str(args.chess960).lower()], capture_output=True, text=True, check=True).stdout
  divide = {}
  for line in output.splitlines():
    move, _, nodes = line.partition(': ')
    if nodes and move not in ('Nodes', 'Time', 'NPS', 'Threads'):
      divide[move] = int(nodes)
  return divide

def check(engine: str, fen: str, depth: int) -> bool:
  expected = expected_divide(fen, depth)
  actual = engine_divide(engine, fen, depth)
  if expected == actual:
    return True
  print(f'MISMATCH at depth {depth}: {fen}')
  for move in sorted(set(expected) | set(actual)):
    if expected.get(move) != actual.get(move):
      print(f'  {move}: expected {expected.get(move, "illegal")}, engine {actual.get(move, "missing")}')
  return False

def check_san(engine: str, fen: str) -> bool:
  board = globals()['board'](fen)
  expected = {move.uci(): board.san(move) for move in board.legal_moves}
  output = subprocess.run([engine, 'moves', fen, '--chess960', str(args.chess960).lower()], capture_output=True, text=True, check=True).stdout
  actual = {}
  for line in output.splitlines()[1:]:
    san, _, uci = line.partition(' (')
    actual[uci.rstrip(')')] = san
  if expected == actual:
    return True
  print(f'SAN MISMATCH: {fen}')
  for move in sorted(set(expected) | set(actual)):
    if expected.get(move) != actual.get(move):
      print(f'  {move}: expected {expected.get(move)}, engine {actual.get(move)}')
  return False

def random_position(plies: int) -> str:
  board = Board.from_chess960_pos(random.randint(0, 959)) if args.chess960 else Board()
  for _ in range(plies):
    moves = list(board.legal_moves)
    if not moves:
      break
    board.push(random.choice(moves))
  if board.is_game_over():
    return random_position(plies)
  return board.fen()

if __name__ == "__main__":
  set_args()

  if args.random:
    fens = [random_position(random.randint(1, 80)) for _ in range(args.random)]
  else:
    fens = [args.fen]

  failures = sum(not (check(args.engine, fen, args.depth) & (not args.san or check_san(args.engine, fen))) for fen in fens)

  print(f'{len(fens) - failures}/{len(fens)} positions OK')
  exit(1 if failures else 0)

#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Compare the Syzygy tablebase probing of the engine with python-chess, on random positions with the material of each table:
the WDL (win, draw or loss) and the DTZ (distance to the next capture or pawn move) of every position must be equal.

python syzygy_check.py --positions 5000
python syzygy_check.py --syzygy syzygy --positions 200 --material KRPvKR
'''

import argparse
import random
import subprocess
from pathlib import Path

import chess
import chess.syzygy

TOOLS = Path(__file__).resolve().parent
DEFAULT_ENGINE = TOOLS.parent / 'engine' / 'build' / 'install' / 'chessy' / 'bin' / 'chessy'
PIECES = {'K': chess.KING, 'Q': chess.QUEEN, 'R': chess.ROOK, 'B': chess.BISHOP, 'N': chess.KNIGHT, 'P': chess.PAWN}

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("--syzygy", help="folder of the tablebases", default=str(TOOLS / 'syzygy'))
  parser.add_argument("--positions", "-n", help="number of random positions", type=int, default=2000)
  parser.add_argument("--material", "-m", help="only this material (such as KRPvKR)")
  parser.add_argument("--engine", "-e", help="engine executable (build it with ./gradlew installDist)", default=str(DEFAULT_ENGINE))
  parser.add_argument("--seed", help="random seed", type=int, default=1)
  args = parser.parse_args()

def random_position(material: str, rng: random.Random) -> chess.Board | None:
  '''A legal position with the material (randomly with colors swapped), or None if the placement was not valid.'''
  white, black = material.split('v')
  if rng.random() < 0.5:
    white, black = black, white
  board = chess.Board(None)
  squares = rng.sample(chess.SQUARES, len(white) + len(black))
  for letter, square in zip(white + black.lower(), squares):
    piece = chess.Piece(PIECES[letter.upper()], chess.WHITE if letter.isupper() else chess.BLACK)
    if piece.piece_type == chess.PAWN and chess.square_rank(square) in (0, 7):
      return None
    board.set_piece_at(square, piece)
  board.turn = rng.choice([chess.WHITE, chess.BLACK])
  # Sometimes en passant: a pawn that just made a double step, next to an enemy pawn
  if rng.random() < 0.3:
    for square in board.pieces(chess.PAWN, not board.turn):
      rank = chess.square_rank(square)
      if rank == (3 if board.turn == chess.BLACK else 4):
        behind = square + (-8 if board.turn == chess.BLACK else 8)
        start = square + (-16 if board.turn == chess.BLACK else 16)
        if board.piece_at(behind) is None and board.piece_at(start) is None:
          board.ep_square = behind
          break
  if not board.is_valid():
    return None
  return board

if __name__ == "__main__":
  set_args()

  rng = random.Random(args.seed)
  materials = [args.material] if args.material else sorted(path.stem for path in Path(args.syzygy).glob('*.rtbw'))
  boards = []
  while len(boards) < args.positions:
    board = random_position(rng.choice(materials), rng)
    if board is not None:
      boards.append(board)

  with chess.syzygy.open_tablebase(args.syzygy) as tablebase:
    expected = [(tablebase.probe_wdl(board), tablebase.probe_dtz(board)) for board in boards]

  fens = '\n'.join(board.fen() for board in boards) + '\n'
  output = subprocess.run([args.engine, 'syzygy', '--syzygy', args.syzygy], input=fens, capture_output=True, text=True, check=True).stdout
  failures = 0
  for line, board, (wdl, dtz) in zip(output.splitlines(), boards, expected):
    _, engine_wdl, engine_dtz = line.split(';')
    if engine_wdl != str(wdl) or engine_dtz != str(dtz):
      failures += 1
      if failures <= 20:
        print(f'MISMATCH {board.fen()}: expected WDL {wdl} DTZ {dtz}, engine WDL {engine_wdl} DTZ {engine_dtz}')
  print(f'{len(boards) - failures}/{len(boards)} positions OK ({len(materials)} tables)')
  exit(1 if failures else 0)

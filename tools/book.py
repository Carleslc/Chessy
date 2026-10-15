#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Build the opening books of Chessy (Polyglot format, https://chessprogramming.org/PolyGlot) from a repertoire of lines:
one book with every line (book.bin) and one book per opening (<opening>.bin), to play only that opening,
and the list of openings (openings.txt).

Each position of a line gets its move, weighted by the weight of the line (the sum when several lines share it).
Engines choose book moves at random in proportion to their weights.

python book.py                    # openings/book.txt -> ../engine/src/main/resources/chessy/book/
python book.py --check --depth 18 # also analyze every move with Stockfish and report the moves that lose too much
'''

import argparse
import struct
from collections import defaultdict
from pathlib import Path

import chess
import chess.engine
import chess.polyglot

TOOLS = Path(__file__).resolve().parent
MAX_PLIES = 24 # 12 moves per side, the limit of the books of CCRL

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("repertoire", nargs='?', help="repertoire file", default=str(TOOLS / 'openings' / 'book.txt'))
  parser.add_argument("--output", "-o", help="folder of the books", default=str(TOOLS.parent / 'engine' / 'src' / 'main' / 'resources' / 'chessy' / 'book'))
  parser.add_argument("--check", help="analyze every move of the lines with Stockfish", action='store_true')
  parser.add_argument("--depth", help="Stockfish depth for --check", type=int, default=16)
  parser.add_argument("--max-loss", help="moves losing more centipawns than this are reported by --check", type=int, default=60)
  parser.add_argument("--stockfish", help="Stockfish command", default="stockfish")
  args = parser.parse_args()

def read_lines(path: str) -> list[tuple[str, int, list[chess.Move], int]]:
  '''Lines of the repertoire: (opening, weight, moves, line number), checking that the moves are legal.'''
  lines = []
  for number, text in enumerate(Path(path).read_text().splitlines(), 1):
    text = text.strip()
    if not text or text.startswith('#'):
      continue
    opening, weight, sans = [field.strip() for field in text.split('|')]
    board = chess.Board()
    moves = []
    for san in sans.split():
      try:
        moves.append(board.push_san(san))
      except ValueError as e:
        raise SystemExit(f'Line {number}: illegal move {san} after {chess.Board().variation_san(board.move_stack)}: {e}')
    if len(moves) > MAX_PLIES:
      raise SystemExit(f'Line {number}: {len(moves)} plies, at most {MAX_PLIES}')
    lines.append((opening, int(weight), moves, number))
  return lines

def polyglot_move(board: chess.Board, move: chess.Move) -> int:
  '''Move in Polyglot: from and to squares, the promotion, and castling as the king taking its own rook.'''
  to_square = move.to_square
  if board.is_castling(move):
    to_square = chess.square(7 if board.is_kingside_castling(move) else 0, chess.square_rank(move.from_square))
  promotion = (move.promotion - 1) if move.promotion else 0 # knight 1, bishop 2, rook 3, queen 4
  return chess.square_file(to_square) | chess.square_rank(to_square) << 3 | \
    chess.square_file(move.from_square) << 6 | chess.square_rank(move.from_square) << 9 | promotion << 12

def write_book(path: Path, lines: list[tuple[str, int, list[chess.Move], int]]):
  weights: dict[tuple[int, int], int] = defaultdict(int)
  for _, weight, moves, _ in lines:
    board = chess.Board()
    for move in moves:
      weights[(chess.polyglot.zobrist_hash(board), polyglot_move(board, move))] += weight
      board.push(move)
  # Entries sorted by key (binary search), and by weight within a key
  entries = sorted(weights.items(), key=lambda entry: (entry[0][0], -entry[1]))
  path.write_bytes(b''.join(struct.pack('>QHHI', key, move, min(weight, 0xFFFF), 0) for (key, move), weight in entries))
  return len(entries)

def check(lines):
  '''Analyze every move of the lines and report those that lose more than --max-loss centipawns for the side that plays them.'''
  cache = {}
  with chess.engine.SimpleEngine.popen_uci(args.stockfish) as stockfish:
    def score(board: chess.Board) -> int:
      key = board.epd()
      if key not in cache:
        info = stockfish.analyse(board, chess.engine.Limit(depth=args.depth))
        cache[key] = info['score'].relative.score(mate_score=10000)
      return cache[key]
    for opening, _, moves, number in lines:
      board = chess.Board()
      for move in moves:
        best = score(board)
        san = board.san(move)
        board.push(move)
        loss = best + score(board) # The score after the move is from the point of view of the opponent
        if loss > args.max_loss:
          print(f'Line {number} ({opening}): {san} at ply {board.ply()} loses {loss} cp')
      print(f'Line {number} ({opening}): {score(board) * (1 if board.turn == chess.WHITE else -1):+} cp for White at the end', flush=True)

if __name__ == "__main__":
  set_args()

  lines = read_lines(args.repertoire)
  if args.check:
    check(lines)
  output = Path(args.output)
  output.mkdir(parents=True, exist_ok=True)
  openings = sorted({opening for opening, _, _, _ in lines})
  print(f'book.bin: {write_book(output / "book.bin", lines)} entries, {len(lines)} lines')
  for opening in openings:
    selected = [line for line in lines if line[0] == opening]
    print(f'{opening}.bin: {write_book(output / f"{opening}.bin", selected)} entries, {len(selected)} lines')
  # The engine lists the openings of the UCI option Opening from this file
  (output / 'openings.txt').write_text(''.join(f'{opening}\n' for opening in openings))

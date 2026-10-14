#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Style metrics of the players of PGN games, to check that the "constrictor" style restricts the opponent:

- Opponent mobility: average legal moves of the opponent (lower is better for a constrictor).
- Space: safe squares of the central files (c-f) in the own ranks 2 to 4, minus those of the opponent.
- Territory: squares attacked in the half of the opponent, minus those attacked by the opponent in the own half.
- Trapped pieces: opponent knights, bishops, rooks and queens with at most one safe move.
- Pieces left: knights, bishops, rooks and queens on the board at move 30 (a constrictor keeps the pieces).
- Wins behind in material: wins after being at least 2 pawns down in material during 6 plies or more.

Metrics are measured in the positions where the opponent is to move, from ply 16 (after the opening).

python style.py games/space.pgn games/material.pgn
'''

import argparse
from collections import defaultdict

import chess
import chess.pgn

VALUES = {chess.PAWN: 1, chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9, chess.KING: 0}
CENTER_FILES = chess.BB_FILE_C | chess.BB_FILE_D | chess.BB_FILE_E | chess.BB_FILE_F
OWN_RANKS = {chess.WHITE: chess.BB_RANK_2 | chess.BB_RANK_3 | chess.BB_RANK_4, chess.BLACK: chess.BB_RANK_7 | chess.BB_RANK_6 | chess.BB_RANK_5}
ENEMY_HALF = {chess.WHITE: chess.BB_RANK_5 | chess.BB_RANK_6 | chess.BB_RANK_7 | chess.BB_RANK_8, chess.BLACK: chess.BB_RANK_1 | chess.BB_RANK_2 | chess.BB_RANK_3 | chess.BB_RANK_4}

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("pgn", help="PGN files", nargs='+')
  parser.add_argument("--min-ply", help="first ply to measure (skip the opening)", type=int, default=16)
  args = parser.parse_args()

def attacked(board: chess.Board, color: chess.Color) -> int:
  squares = 0
  for square in chess.SquareSet(board.occupied_co[color]):
    squares |= int(board.attacks(square))
  return squares

def pawn_attacks(board: chess.Board, color: chess.Color) -> int:
  squares = 0
  for square in board.pieces(chess.PAWN, color):
    squares |= int(board.attacks(square))
  return squares

def space(board: chess.Board, color: chess.Color) -> int:
  safe = CENTER_FILES & OWN_RANKS[color] & ~int(board.pieces(chess.PAWN, color)) & ~pawn_attacks(board, not color)
  return chess.popcount(safe)

def material(board: chess.Board, color: chess.Color) -> int:
  return sum(VALUES[piece.piece_type] for piece in board.piece_map().values() if piece.color == color)

def trapped(board: chess.Board, color: chess.Color) -> int:
  enemy_pawn_attacks = pawn_attacks(board, not color)
  count = 0
  for piece_type in (chess.KNIGHT, chess.BISHOP, chess.ROOK, chess.QUEEN):
    for square in board.pieces(piece_type, color):
      safe = int(board.attacks(square)) & ~board.occupied_co[color] & ~enemy_pawn_attacks
      if chess.popcount(safe) <= 1:
        count += 1
  return count

def pieces(board: chess.Board) -> int:
  return sum(1 for piece in board.piece_map().values() if piece.piece_type not in (chess.PAWN, chess.KING))

if __name__ == "__main__":
  set_args()

  totals = defaultdict(lambda: defaultdict(float))
  counts = defaultdict(lambda: defaultdict(int))

  for path in args.pgn:
    with open(path) as pgn:
      while game := chess.pgn.read_game(pgn):
        names = {chess.WHITE: game.headers.get('White', '?'), chess.BLACK: game.headers.get('Black', '?')}
        result = game.headers.get('Result', '*')
        board = game.board()
        behind = {chess.WHITE: 0, chess.BLACK: 0}
        was_behind = {chess.WHITE: False, chess.BLACK: False}
        pieces_at_30 = None

        for ply, move in enumerate(game.mainline_moves()):
          board.push(move)
          if board.ply() == 60:
            pieces_at_30 = pieces(board)
          for color in (chess.WHITE, chess.BLACK):
            behind[color] = behind[color] + 1 if material(board, color) - material(board, not color) <= -2 else 0
            was_behind[color] |= behind[color] >= 6
          if board.ply() < args.min_ply or board.is_game_over():
            continue
          # The player who just moved, and the opponent to move
          player = not board.turn
          name = names[player]
          totals[name]['opponent mobility'] += board.legal_moves.count()
          totals[name]['space'] += space(board, player) - space(board, not player)
          totals[name]['territory'] += chess.popcount(attacked(board, player) & ENEMY_HALF[player]) - chess.popcount(attacked(board, not player) & ENEMY_HALF[not player])
          totals[name]['opponent trapped pieces'] += trapped(board, not player)
          counts[name]['positions'] += 1

        for color in (chess.WHITE, chess.BLACK):
          name = names[color]
          counts[name]['games'] += 1
          if pieces_at_30 is not None:
            totals[name]['pieces left at move 30'] += pieces_at_30
            counts[name]['games at move 30'] += 1
          won = (result == '1-0' and color == chess.WHITE) or (result == '0-1' and color == chess.BLACK)
          if won:
            counts[name]['wins'] += 1
            counts[name]['wins behind in material'] += was_behind[color]

  for name in sorted(totals):
    positions = max(counts[name]['positions'], 1)
    print(f'{name} ({counts[name]["games"]} games, {counts[name]["positions"]} positions)')
    for metric in ('opponent mobility', 'space', 'territory', 'opponent trapped pieces'):
      print(f'  {metric:28} {totals[name][metric] / positions:7.2f}')
    print(f'  {"pieces left at move 30":28} {totals[name]["pieces left at move 30"] / max(counts[name]["games at move 30"], 1):7.2f}')
    wins = counts[name]['wins']
    print(f'  {"wins behind in material":28} {counts[name]["wins behind in material"]}/{wins} ({counts[name]["wins behind in material"] / max(wins, 1):.0%})')

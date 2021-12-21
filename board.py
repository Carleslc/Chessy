#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Display a board given its FEN notation.

python board.py --fen "7r/3B4/8/p5pp/4Qbk1/1P4B1/P4PKP/8 b - - 1 39" -c
'''

import argparse

from chess import Board, STARTING_FEN

from app.app import App
from utils.visualization import board_svg
from utils.error import error

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("--fen", "-f", help="board representation with fen notation", default=STARTING_FEN)
  parser.add_argument("--coordinates", "-c", help="show coordinates", action='store_true')
  parser.add_argument("--black", "-b", help="set the point of view to black", action='store_false')
  args = parser.parse_args()

def display_board(app: App, board: Board, validation: bool = True, **kwargs):
  print(board.fen(), end='\n')

  if validation:
    print(board.status().name)

  outcome = board.outcome(claim_draw=True)

  if outcome:
    print(outcome.termination.name)
    print(outcome.result())
  
  if board.has_insufficient_material(board.turn):
    print('INSUFFICIENT_MATERIAL')
  
  check = board.king(board.turn) if board.is_checkmate() else None

  app.display(board_svg(board, check=check, **kwargs))

if __name__ == "__main__":
  set_args()

  try:
    board = Board(args.fen)
  except ValueError as e:
    error(e)

  app = App(height=500)

  app.title(board.board_fen())

  display_board(app, board, orientation=args.black, coordinates=args.coordinates)

  app.start()

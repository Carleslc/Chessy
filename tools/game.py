#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Display a game given its PGN notation.

python game.py examples/001.pgn
'''

import argparse

from chess.pgn import Game, read_game, skip_game

from app.app import App
from utils.error import error
from utils.date import pgn_datetime
from board import display_board

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("pgn", help="game file with PGN notation")
  parser.add_argument("--end", "-e", help="skips to the last move", action='store_true')
  parser.add_argument("--coordinates", "-c", help="show coordinates", action='store_true')
  parser.add_argument("--black", "-b", help="set the point of view to black", action='store_false')
  parser.add_argument("--match", "-m", help="match number", default=1, type=int)
  args = parser.parse_args()

def display_game(app: App, game: Game, end: bool = False, **kwargs):
  print(game, end='\n\n')

  state = game.end() if end else game.root()

  display_board(app, state.board(), validation=False, **kwargs)
  
  if not end:
    def display_move():
      print(state.parent.board().san(state.move))
      display_board(app, state.board(), validation=False, move=state.move, **kwargs)
      if state.comment:
        print(state.comment)

    prev_move_btn = None

    def previous_move():
      nonlocal state

      state = state.parent

      if state is game.root():
        display_board(app, state.board(), validation=False, **kwargs)

        app.window.remove(prev_move_btn)
      else:
        display_move()

        if not state.is_end():
          next_move_btn.setText('➡')

    def next_move():
      nonlocal state

      if state is game.end():
        app.quit()
      else:
        if state is game.root():
          add_prev_move()

        state = state.next()
        
        display_move()

        if state.is_end():
          end_text = next_move_btn_text()
          next_move_btn.setText(end_text)
    
    def add_prev_move():
      nonlocal prev_move_btn
      prev_move_btn = app.window.add_button('⬅', previous_move, prepend=True)
    
    def next_move_btn_text() -> str:
      board = state.board()
      outcome = board.outcome(claim_draw=True)
      if outcome:
        return outcome.termination.name
      elif state.is_end():
        status = f"END {game.headers['Result']}"
        print(status)
        return status
      return '➡'
    
    next_move_btn = app.window.add_button(next_move_btn_text(), next_move)

def get_title(game: Game):
  title = game.headers.get('Event')

  if title:
    dt = pgn_datetime(game.headers)
    if dt:
      title += f' - {dt}'
  else:
    title = 'Game'
  
  return title

if __name__ == "__main__":
  set_args()

  app = App(height=500 if args.end else 550)

  try:
    with open(args.pgn) as pgn:
      i = 1
      can_read = True
      while can_read and i < args.match:
        can_read = skip_game(pgn)
        i += 1
      if can_read:
        game = read_game(pgn)
      if not can_read or not game:
        raise ValueError(f"Game #{args.match} not found")
  except ValueError as e:
    error(e)
  
  app.title(get_title(game))

  display_game(app, game, args.end, orientation=args.black, coordinates=args.coordinates)

  app.start()

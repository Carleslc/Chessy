#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
UCI adapter for Chessy v0 (the first implementation, 2018-2019, branch v0), which only plays in the console:
it can then play in fastchess, chess GUIs or against the other versions of Chessy.

For each move, v0 is started from the FEN of the position with the time of the move, and its best move is read.
v0 does not see the previous positions, so it does not avoid repetitions. Every move pays the start of the JVM
(about half a second), so use time controls of at least 1 minute.

Build v0 first (in tools/engines/v0, ignored by git):

git worktree add engines/v0 v0
cd engines/v0 && mvn -q compile dependency:build-classpath -Dmdep.outputFile=classpath.txt

fastchess -engine cmd=python3 args=v0_uci.py name=Chessy-v0 -engine cmd=stockfish ... -each tc=60+0.6
'''

import subprocess
import sys
from pathlib import Path

import chess

V0 = Path(__file__).resolve().parent / 'engines' / 'v0'
JVM_START = 0.6 # seconds
MIN_TIME = 0.2 # seconds to think

def send(line: str):
  print(line, flush=True)

def think_time(board: chess.Board, tokens: list[str]) -> float:
  '''Seconds to think: movetime, or a part of the remaining time plus the increment.'''
  values = dict(zip(tokens[1::2], tokens[2::2]))
  if 'movetime' in values:
    return max(MIN_TIME, int(values['movetime']) / 1000 - JVM_START)
  side = 'w' if board.turn == chess.WHITE else 'b'
  remaining = int(values.get(f'{side}time', 60000)) / 1000
  increment = int(values.get(f'{side}inc', 0)) / 1000
  moves_to_go = int(values.get('movestogo', 30))
  return max(MIN_TIME, min(remaining / moves_to_go + increment * 0.8, remaining / 3) - JVM_START)

def best_move(board: chess.Board, seconds: float) -> chess.Move:
  classpath = f'{V0 / "target" / "classes"}:{(V0 / "classpath.txt").read_text().strip()}'
  command = ['java', '-cp', classpath, 'core.ChessyKt', '--fen', board.fen(), '--time', f'{seconds:.2f}']
  if board.turn == chess.BLACK:
    command.append('--black')
  # Without input, v0 plays its move and stops when it is the turn of the human
  output = subprocess.run(command, cwd=V0, stdin=subprocess.DEVNULL, capture_output=True, text=True).stdout
  for line in output.splitlines():
    if line.startswith('Best: '):
      return board.parse_san(line.split()[1])
  raise RuntimeError(f'Chessy v0 did not move:\n{output}')

def main():
  board = chess.Board()
  for line in sys.stdin:
    tokens = line.split()
    if not tokens:
      continue
    command = tokens[0]
    if command == 'uci':
      send('id name Chessy v0')
      send('id author Carleslc')
      send('uciok')
    elif command == 'isready':
      send('readyok')
    elif command == 'ucinewgame':
      board = chess.Board()
    elif command == 'position':
      if 'fen' in tokens:
        end = tokens.index('moves') if 'moves' in tokens else len(tokens)
        board = chess.Board(' '.join(tokens[tokens.index('fen') + 1:end]))
      else:
        board = chess.Board()
      if 'moves' in tokens:
        for move in tokens[tokens.index('moves') + 1:]:
          board.push_uci(move)
    elif command == 'go':
      send(f'bestmove {best_move(board, think_time(board, tokens)).uci()}')
    elif command == 'quit':
      break

if __name__ == '__main__':
  main()

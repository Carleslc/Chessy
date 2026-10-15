#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Play games between two UCI engines (Snaky against itself by default), checking that every move is legal,
the protocol works and no engine runs out of time. Games are saved in PGN.

python match.py --games 100 --tc 10+0.1
python match.py --games 20 --engine2 stockfish --option2 UCI_LimitStrength=true --option2 UCI_Elo=1350
'''

import argparse
import math
import random
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from threading import Lock

import chess
import chess.engine
import chess.pgn

DEFAULT_ENGINE = Path(__file__).resolve().parent.parent / 'engine' / 'build' / 'install' / 'snaky' / 'bin' / 'snaky'

MAX_PLIES = 600

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("--games", "-n", help="number of games (colors alternate)", type=int, default=10)
  parser.add_argument("--engine1", "-e1", help="first engine command (default: Snaky, build it with ./gradlew installDist)", default=str(DEFAULT_ENGINE))
  parser.add_argument("--engine2", "-e2", help="second engine command (default: same as the first)")
  parser.add_argument("--option1", "-o1", help="UCI option NAME=VALUE for the first engine", action='append', default=[])
  parser.add_argument("--option2", "-o2", help="UCI option NAME=VALUE for the second engine", action='append', default=[])
  parser.add_argument("--tc", help="time control in seconds: base+increment (e.g. 10+0.1)")
  parser.add_argument("--movetime", help="seconds per move when there is no time control", type=float, default=0.05)
  parser.add_argument("--random-plies", help="random plies at the start of each game, for variety", type=int, default=8)
  parser.add_argument("--concurrency", "-c", help="games played at the same time", type=int, default=4)
  parser.add_argument("--seed", help="random seed for the openings", type=int, default=1)
  parser.add_argument("--pgn", help="file to save the games", default="games/match.pgn")
  args = parser.parse_args()

def parse_options(options: list[str]) -> dict[str, str]:
  return dict(option.split('=', 1) for option in options)

@dataclass
class Stats:
  results: Counter = field(default_factory=Counter) # From the point of view of engine 1: win, draw, loss
  terminations: Counter = field(default_factory=Counter)
  errors: list[str] = field(default_factory=list)
  moves: int = 0
  move_time: float = 0
  lock: Lock = field(default_factory=Lock)

def open_engine(command: str, options: dict[str, str]) -> chess.engine.SimpleEngine:
  engine = chess.engine.SimpleEngine.popen_uci(command.split())
  # One thread by default (Snaky plays with 4), as several games run at the same time
  if 'Threads' in engine.options and 'Threads' not in options:
    options = {'Threads': '1', **options}
  if options:
    engine.configure(options)
  return engine

def random_opening(board: chess.Board, plies: int, rng: random.Random):
  for _ in range(plies):
    moves = list(board.legal_moves)
    if not moves:
      break
    board.push(rng.choice(moves))
  if board.is_game_over(claim_draw=True):
    board.reset()

def play_game(index: int, engines: list[chess.engine.SimpleEngine], names: list[str], stats: Stats) -> chess.pgn.Game:
  board = chess.Board()
  random_opening(board, args.random_plies, random.Random(args.seed * 100_000 + index))

  # Engine 1 plays white in even games
  white, black = (0, 1) if index % 2 == 0 else (1, 0)
  base, increment = (float(x) for x in args.tc.split('+')) if args.tc else (None, 0)
  clocks = {chess.WHITE: base, chess.BLACK: base}
  termination = None

  while termination is None:
    outcome = board.outcome(claim_draw=True)
    if outcome:
      termination = outcome.termination.name
      break
    if board.ply() >= MAX_PLIES:
      termination = 'MAX_PLIES'
      break

    engine = engines[white if board.turn == chess.WHITE else black]
    if base is not None:
      limit = chess.engine.Limit(white_clock=clocks[chess.WHITE], black_clock=clocks[chess.BLACK], white_inc=increment, black_inc=increment)
    else:
      limit = chess.engine.Limit(time=args.movetime)

    start = time.monotonic()
    try:
      result = engine.play(board, limit, game=index)
    except chess.engine.EngineError as e: # Includes illegal moves
      termination = 'ERROR'
      with stats.lock:
        stats.errors.append(f'Game {index + 1}, {board.fen()}: {e}')
      break
    elapsed = time.monotonic() - start

    with stats.lock:
      stats.moves += 1
      stats.move_time += elapsed

    if base is not None:
      clocks[board.turn] -= elapsed
      if clocks[board.turn] < 0:
        termination = 'TIME_FORFEIT'
        with stats.lock:
          stats.errors.append(f'Game {index + 1}: {names[white if board.turn == chess.WHITE else black]} lost on time')
        break
      clocks[board.turn] += increment

    if result.move is None:
      termination = 'NO_MOVE'
      with stats.lock:
        stats.errors.append(f'Game {index + 1}, {board.fen()}: no move')
      break
    board.push(result.move)

  if termination in ('TIME_FORFEIT', 'ERROR', 'NO_MOVE'):
    result = '0-1' if board.turn == chess.WHITE else '1-0'
  elif termination == 'MAX_PLIES':
    result = '1/2-1/2'
  else:
    result = board.outcome(claim_draw=True).result()

  game = chess.pgn.Game.from_board(board)
  game.headers['Event'] = 'Snaky match'
  game.headers['Round'] = str(index + 1)
  game.headers['White'] = names[white]
  game.headers['Black'] = names[black]
  game.headers['Result'] = result
  game.headers['Termination'] = termination
  if args.tc:
    game.headers['TimeControl'] = args.tc

  score = {'1-0': 1, '0-1': 0, '1/2-1/2': 0.5}[result]
  engine1_score = score if white == 0 else 1 - score
  with stats.lock:
    stats.results[{1: 'win', 0.5: 'draw', 0: 'loss'}[engine1_score]] += 1
    stats.terminations[termination] += 1
  return game

def elo_difference(score: float) -> float:
  if score <= 0 or score >= 1:
    return math.inf if score >= 1 else -math.inf
  return -400 * math.log10(1 / score - 1)

def worker(indexes: list[int], stats: Stats, games: dict):
  engines = [open_engine(args.engine1, parse_options(args.option1)), open_engine(args.engine2 or args.engine1, parse_options(args.option2))]
  names = [engine.id.get('name', 'engine') for engine in engines]
  if names[0] == names[1]:
    names = [f'{names[0]} (1)', f'{names[1]} (2)']
  try:
    for index in indexes:
      games[index] = play_game(index, engines, names, stats)
      done = len(games)
      if done % 10 == 0 or done == args.games:
        print(f'{done}/{args.games} games {dict(stats.results)}', flush=True)
  finally:
    for engine in engines:
      engine.quit()

if __name__ == "__main__":
  set_args()

  stats = Stats()
  games = {}
  start = time.monotonic()
  with ThreadPoolExecutor(args.concurrency) as pool:
    chunks = [list(range(i, args.games, args.concurrency)) for i in range(args.concurrency)]
    for future in [pool.submit(worker, chunk, stats, games) for chunk in chunks if chunk]:
      future.result()

  pgn_path = Path(args.pgn)
  pgn_path.parent.mkdir(parents=True, exist_ok=True)
  with open(pgn_path, 'w') as pgn:
    for index in sorted(games):
      print(games[index], file=pgn, end='\n\n')

  played = sum(stats.results.values())
  score = (stats.results['win'] + stats.results['draw'] / 2) / played if played else 0
  print()
  print(f'Games: {played} in {time.monotonic() - start:.0f} s, saved to {pgn_path}')
  print(f'Engine 1: +{stats.results["win"]} ={stats.results["draw"]} -{stats.results["loss"]} (score {score:.1%}, Elo difference {elo_difference(score):+.0f})')
  print(f'Terminations: {dict(stats.terminations)}')
  print(f'Moves: {stats.moves}, average time per move: {stats.move_time / max(stats.moves, 1) * 1000:.1f} ms')
  print(f'Errors (illegal moves, protocol, time forfeits): {len(stats.errors)}')
  for error in stats.errors[:20]:
    print(f'  {error}')
  exit(1 if stats.errors else 0)

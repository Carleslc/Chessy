#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Review the games of a player with Stockfish, to find its weak points: the loss of each move (centipawns from the point
of view of the player, against the best move of Stockfish), by phase of the game and by time left on the clock,
and the mistakes, with the position (FEN) to analyze them.

Positions already decided (Stockfish beyond --decided) are not counted: there any move wins or loses.

python review.py games/lichess/snaky-bot-vs-bots.pgn --player Snaky-Bot
python review.py games/lichess/snaky-bot-vs-bots.pgn --player Snaky-Bot --depth 20 --mistake 80 --epd games/mistakes.epd
'''

import argparse
import sys
from collections import defaultdict

import chess
import chess.engine
import chess.pgn

PIECE_VALUES = {chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9}

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("pgn", help="PGN file with the games")
  parser.add_argument("--player", "-p", help="name of the player to review (as in the PGN)", required=True)
  parser.add_argument("--stockfish", help="Stockfish command", default="stockfish")
  parser.add_argument("--depth", "-d", help="depth of Stockfish (default: 18)", type=int, default=18)
  parser.add_argument("--max-time", help="maximum seconds of each analysis (default: 3)", type=float, default=3)
  parser.add_argument("--threads", help="threads of Stockfish (default: 8)", type=int, default=8)
  parser.add_argument("--mistake", help="loss in centipawns of a mistake (default: 100)", type=int, default=100)
  parser.add_argument("--decided", help="positions beyond this score (centipawns) are not counted (default: 500)", type=int, default=500)
  parser.add_argument("--from-move", help="first move reviewed (default: 1)", type=int, default=1)
  parser.add_argument("--epd", help="file to save the positions of the mistakes, with the best move of Stockfish (bm)")
  args = parser.parse_args()

def phase(board: chess.Board) -> str:
  '''Opening (first 12 moves), middlegame, or the kind of endgame (material of pieces without pawns: 13 or less).'''
  material = sum(value * len(board.pieces(piece, color)) for piece, value in PIECE_VALUES.items() for color in chess.COLORS)
  if board.fullmove_number <= 12:
    return 'opening'
  if material > 26:
    return 'middlegame'
  queens = len(board.pieces(chess.QUEEN, chess.WHITE)) + len(board.pieces(chess.QUEEN, chess.BLACK))
  rooks = len(board.pieces(chess.ROOK, chess.WHITE)) + len(board.pieces(chess.ROOK, chess.BLACK))
  minors = material - 9 * queens - 5 * rooks
  if material == 0:
    return 'pawn endgame'
  if queens:
    return 'queen endgame'
  if rooks and not minors:
    return 'rook endgame'
  if rooks:
    return 'rook and minor endgame'
  return 'minor piece endgame'

def clock_range(seconds: float | None) -> str:
  if seconds is None:
    return '?'
  return next(label for limit, label in [(10, '< 10 s'), (30, '10-30 s'), (60, '30-60 s'), (float('inf'), '> 60 s')] if seconds < limit)

def score(info: chess.engine.InfoDict, color: chess.Color) -> int:
  return info['score'].pov(color).score(mate_score=10000)

if __name__ == "__main__":
  set_args()
  stockfish = chess.engine.SimpleEngine.popen_uci(args.stockfish)
  stockfish.configure({'Threads': args.threads, 'Hash': 256})
  limit = chess.engine.Limit(depth=args.depth, time=args.max_time)
  losses = defaultdict(list)
  mistakes = []
  results = defaultdict(int)

  with open(args.pgn) as pgn:
    while (game := chess.pgn.read_game(pgn)):
      if args.player not in (game.headers['White'], game.headers['Black']):
        continue
      color = game.headers['White'] == args.player
      opponent = game.headers['Black' if color else 'White']
      result = game.headers['Result']
      print(f"{game.headers['White']} - {game.headers['Black']} {result} {game.headers.get('Site', '')}", file=sys.stderr)
      results['draw' if result == '1/2-1/2' else 'win' if (result == '1-0') == color else 'loss'] += 1
      board = game.board()
      for node in game.mainline():
        move = node.move
        if board.turn == color and board.fullmove_number >= args.from_move:
          best = stockfish.analyse(board, limit)
          best_score = score(best, color)
          if abs(best_score) <= args.decided:
            played = best if move == best['pv'][0] else stockfish.analyse(board, limit, root_moves=[move])
            loss = max(0, best_score - score(played, color))
            kind = phase(board)
            clock = node.clock()
            losses['all'].append(loss)
            losses[kind].append(loss)
            losses['clock ' + clock_range(clock)].append(loss)
            losses['checks' if board.gives_check(move) else 'not checks'].append(loss)
            if loss >= args.mistake:
              mistakes.append((loss, game.headers['Site'], opponent, board.fullmove_number, board.san(move), board.san(best['pv'][0]),
                               best_score, kind, clock, board.copy()))
        board.push(move)
  stockfish.quit()

  print(f"{args.player}: {results['win']} wins, {results['draw']} draws, {results['loss']} losses")
  print(f"\nAverage loss per move (centipawns), positions within ±{args.decided}, Stockfish depth {args.depth}:")
  for key, values in sorted(losses.items(), key=lambda item: -len(item[1])):
    mistakes_count = sum(loss >= args.mistake for loss in values)
    print(f"  {key:24} {len(values):4} moves  {sum(values) / len(values):6.1f}  mistakes (>= {args.mistake}): {mistakes_count}")
  print(f"\nMistakes ({len(mistakes)}):")
  for loss, site, opponent, number, played, best, best_score, kind, clock, board in sorted(mistakes, key=lambda m: -m[0]):
    dots = '.' if board.turn == chess.WHITE else '...'
    print(f"  -{loss:4} {number}{dots}{played} (best {best}, {best_score:+}) {kind}, clock {clock or 0:.0f} s, vs {opponent} {site}")
    print(f"        {board.fen()}")
  if args.epd:
    with open(args.epd, 'w') as epd:
      for loss, site, opponent, number, played, best, best_score, kind, clock, board in mistakes:
        epd.write(board.epd(bm=board.parse_san(best), id=f'{site} {number} {played}', c0=f'{kind}, loss {loss}') + '\n')

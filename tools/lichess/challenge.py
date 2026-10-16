#!/usr/bin/python3
# -*- coding: utf-8 -*-

'''
Challenge a player or a bot with the BOT account of Snaky, when you want to watch a game: lichess-bot (running with
run.sh or Docker) plays it like any other game. The challenge waits until it is accepted or declined, and with --bot
it challenges online bots with a rating close to Snaky until one accepts (Ctrl+C cancels the challenge).
Bots can play 100 games a day against bots.

python challenge.py Maia1 --tc 3+2
python challenge.py someone --days 3 --color white
python challenge.py --bot --tc 5+3 --rated
python challenge.py --bot --tc 3+2 --min-rating 2200 --max-rating 2600 --variant chess960
python challenge.py --bot --tc 3+2 --list

The token is read from LICHESS_BOT_TOKEN, or from the .env file of this folder.
'''

import argparse
import contextlib
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = 'https://lichess.org'

# Seconds between the checks that Snaky is not playing another game while it waits for an answer
PLAYING_CHECK_INTERVAL = 5

# Rating of Snaky for the default range of --bot while its own rating is provisional
PROVISIONAL_RATING = 2500

# Lichess speeds by estimated game duration (seconds of base time + 40 times the increment)
SPEEDS = [(29, 'ultraBullet'), (179, 'bullet'), (479, 'blitz'), (1499, 'rapid'), (float('inf'), 'classical')]

def set_args():
  global args
  parser = argparse.ArgumentParser()
  parser.add_argument("username", help="player or bot to challenge (or --bot)", nargs='?')
  parser.add_argument("--bot", help="challenge an online bot with a rating close to Snaky, until one accepts", action='store_true')
  parser.add_argument("--tc", help="time control in minutes+seconds of increment (default: 3+2)", default='3+2')
  parser.add_argument("--days", help="correspondence game with these days per move (1, 2, 3, 5, 7, 10 or 14) instead of --tc", type=int)
  parser.add_argument("--color", help="color of Snaky: random (default), white or black", default='random', choices=['random', 'white', 'black'])
  parser.add_argument("--variant", help="standard (default) or chess960", default='standard', choices=['standard', 'chess960'])
  parser.add_argument("--rated", help="rated game (casual by default)", action='store_true')
  parser.add_argument("--min-rating", help="minimum rating of the bots of --bot (default: rating of Snaky - 300, or 2200 while provisional)", type=int)
  parser.add_argument("--max-rating", help="maximum rating of the bots of --bot (default: rating of Snaky + 300, or 2800 while provisional)", type=int)
  parser.add_argument("--tries", help="maximum number of bots challenged with --bot (default: 10)", type=int, default=10)
  parser.add_argument("--wait", help="seconds to wait for each answer (default: 60, 15 for bots)", type=int)
  parser.add_argument("--list", help="list the bots that --bot would challenge, without challenging them", action='store_true')
  parser.add_argument("--force", help="challenge even if Snaky is playing (or starts) another game", action='store_true')
  args = parser.parse_args()
  if bool(args.username) == args.bot:
    parser.error('give a username or --bot')

def read_token() -> str:
  token = os.environ.get('LICHESS_BOT_TOKEN')
  env = Path(__file__).resolve().parent / '.env'
  if not token and env.exists():
    for line in env.read_text().splitlines():
      if line.startswith('LICHESS_BOT_TOKEN='):
        token = line.split('=', 1)[1].strip().strip('"\'')
  if not token:
    sys.exit('Set the token of the BOT account in LICHESS_BOT_TOKEN (or in tools/lichess/.env)')
  return token

def request(method: str, path: str, data: dict | None = None, timeout: float = 30):
  body = urllib.parse.urlencode(data).encode() if data is not None else None
  headers = {'Authorization': f'Bearer {TOKEN}'}
  return urllib.request.urlopen(urllib.request.Request(API + path, body, headers, method=method), timeout=timeout)

def get_json(path: str):
  with request('GET', path) as response:
    return json.load(response)

def speed() -> str:
  if args.days:
    return 'correspondence'
  minutes, increment = (float(part) for part in args.tc.split('+'))
  return next(name for limit, name in SPEEDS if minutes * 60 + 40 * increment <= limit)

def challenge_data() -> dict:
  data = {'rated': str(args.rated).lower(), 'color': args.color, 'variant': args.variant, 'keepAliveStream': 'true'}
  if args.days:
    data['days'] = args.days
  else:
    minutes, increment = args.tc.split('+')
    data['clock.limit'] = round(float(minutes) * 60)
    data['clock.increment'] = int(increment)
  return data

def challenge(username: str, wait: float) -> str | None:
  '''Challenges [username] and waits for the answer: the game ID if it is accepted, None otherwise.'''
  try:
    response = request('POST', f'/api/challenge/{username}', challenge_data(), timeout=wait + 30)
  except urllib.error.HTTPError as error:
    print(f'  {username}: {json.loads(error.read() or b"{}").get("error", error.reason)}')
    return None
  challenge_id = None
  deadline = time.monotonic() + wait
  next_check = time.monotonic() + PLAYING_CHECK_INTERVAL
  with response:
    # The stream sends the challenge, empty lines to keep it alive, and {"done": "accepted"} (or declined, canceled)
    try:
      for line in response:
        if line.strip():
          message = json.loads(line)
          challenge_id = challenge_id or message.get('challenge', message).get('id')
          if 'done' in message:
            print(f'  {username}: {message["done"]}')
            return challenge_id if message['done'] == 'accepted' else None
        if time.monotonic() > deadline:
          break
        # Snaky plays one game at a time: if another game starts while waiting, the challenge is canceled
        if not args.force and time.monotonic() > next_check:
          next_check = time.monotonic() + PLAYING_CHECK_INTERVAL
          if (game := playing(challenge_id)):
            cancel(challenge_id)
            raise AnotherGame(game)
    except TimeoutError:
      pass
    except KeyboardInterrupt:
      # Ctrl+C cancels the challenge (without its ID, it expires when the connection is closed)
      cancel(challenge_id)
      print(f'  {username}: canceled')
      raise
  cancel(challenge_id)
  print(f'  {username}: no answer in {wait:.0f} s, canceled')
  return None

class AnotherGame(Exception):
  """Snaky started another game while waiting for the answer to a challenge."""

def playing(except_game: str | None = None) -> str | None:
  """A game that Snaky is playing (other than [except_game]), or None."""
  games = get_json('/api/account/playing').get('nowPlaying', [])
  return next((game['gameId'] for game in games if game['gameId'] != except_game), None)

def cancel(challenge_id: str | None):
  if challenge_id:
    with contextlib.suppress(urllib.error.URLError):
      request('POST', f'/api/challenge/{challenge_id}/cancel').close()

def online_bots(me: dict) -> list[str]:
  '''Online bots that play the speed and variant, with a rating within the range, in random order.'''
  perf = me.get('perfs', {}).get(speed(), {})
  # A provisional rating (the first games) is not reliable yet
  rating = PROVISIONAL_RATING if perf.get('prov', True) else perf['rating']
  low, high = args.min_rating or rating - 300, args.max_rating or rating + 300
  perf = 'chess960' if args.variant == 'chess960' else speed()
  bots = []
  with request('GET', '/api/bot/online?nb=300') as response:
    for line in response:
      if line.strip():
        bot = json.loads(line)
        bot_rating = bot.get('perfs', {}).get(perf, {}).get('rating')
        if bot['id'] != me['id'] and not bot.get('tosViolation') and bot_rating and low <= bot_rating <= high:
          bots.append(bot['username'])
  print(f'{len(bots)} online bots with a {perf} rating from {low} to {high}')
  random.shuffle(bots)
  return bots

if __name__ == "__main__":
  set_args()
  TOKEN = read_token()
  me = get_json('/api/account')
  if me.get('title') != 'BOT':
    sys.exit(f'{me["username"]} is not a BOT account')
  if (game := playing()) and not args.force:
    sys.exit(f'{me["username"]} is playing {API}/{game} (--force to challenge anyway)')

  game = (args.days and 'correspondence, ' + str(args.days) + ' days per move') or args.tc
  print(f'{me["username"]} challenges ({game}, {args.variant}, {"rated" if args.rated else "casual"}, color {args.color})')
  opponents = online_bots(me)[:args.tries] if args.bot else [args.username]
  if args.list:
    print('\n'.join(opponents))
    sys.exit()
  wait = args.wait or (15 if args.bot else 60)
  try:
    for opponent in opponents:
      game_id = challenge(opponent, wait)
      if game_id:
        print(f'Watch the game: {API}/{game_id}')
        break
    else:
      sys.exit('No challenge was accepted')
  except KeyboardInterrupt:
    sys.exit('Challenge canceled')
  except AnotherGame as game:
    sys.exit(f'Challenge canceled: {me["username"]} started another game, {API}/{game}')

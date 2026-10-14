#!/bin/sh
# Start Chessy as a Lichess BOT with lichess-bot (see README.md in this folder).
#
# LICHESS_BOT_TOKEN=xxx ./run.sh             # play the challenges
# LICHESS_BOT_TOKEN=xxx ./run.sh --upgrade   # convert the account to BOT (once, irreversible), then play
# ./run.sh --test                            # play a game offline against Stockfish 2000, without a token
#                                            # (needs pytest in lichess-bot: pip install -r test_bot/test-requirements.txt)
#
# lichess-bot is expected next to this repository (../lichess-bot from the root of Chessy),
# or in the folder given by the LICHESS_BOT_DIR environment variable.
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$DIR/../.." && pwd)
LICHESS_BOT_DIR=${LICHESS_BOT_DIR:-$(dirname "$ROOT")/lichess-bot}

if [ -z "$LICHESS_BOT_TOKEN" ] && [ "$1" != "--test" ]; then
  echo "Set the token of the BOT account: LICHESS_BOT_TOKEN=xxx $0" >&2
  exit 1
fi
if [ ! -f "$LICHESS_BOT_DIR/lichess-bot.py" ]; then
  echo "lichess-bot not found in $LICHESS_BOT_DIR, install it first (see $DIR/README.md)" >&2
  exit 1
fi

# Build the engine and point the configuration to it
(cd "$ROOT/engine" && ./gradlew -q installDist)
sed "s|^  dir: .*|  dir: \"$ROOT/engine/build/install/chessy/bin/\"|" "$DIR/config.yml" > "$LICHESS_BOT_DIR/config.yml"

cd "$LICHESS_BOT_DIR"
if [ -d venv ]; then
  . venv/bin/activate
fi
if [ "$1" = "--test" ]; then
  # The test uses the mocked lichess.org of the tests of lichess-bot, so it runs inside its test_bot package
  cp "$DIR/test_offline.py" test_bot/test_chessy_offline.py
  trap 'rm -f test_bot/test_chessy_offline.py' EXIT
  python -m pytest -q -s -p no:cacheprovider test_bot/test_chessy_offline.py
  exit
fi
if [ "$1" = "--upgrade" ]; then
  exec python lichess-bot.py --config config.yml -u
fi
exec python lichess-bot.py --config config.yml

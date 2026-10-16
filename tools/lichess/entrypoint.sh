#!/bin/sh
# Entry point of the Docker image of the bot: writes the configuration of lichess-bot with the engine and the tablebases
# of the container, and runs one of these commands:
#
#   play      Play the challenges (default). Needs LICHESS_BOT_TOKEN.
#   upgrade   Convert the account to BOT (once, irreversible), then play. Needs LICHESS_BOT_TOKEN.
#   test      Play a game offline against a mocked lichess.org, without a token.
#   challenge Challenge a player or online bots, to watch a game of the running bot (arguments of challenge.py).
#
# The configuration is /snaky-config/config.yml (tools/lichess/config.yml): mount another file there to change it.
set -e

SYZYGY=""
if ls /syzygy/*.rtbw > /dev/null 2>&1; then
  SYZYGY=/syzygy
fi
sed -e 's|^  dir: .*|  dir: "/snaky/bin/"|' \
    -e "s|^    SyzygyPath: .*|    SyzygyPath: \"$SYZYGY\"|" /snaky-config/config.yml > config.yml
echo "$(ls /snaky/lib | grep '^snaky-' | sed 's/\.jar$//'), tablebases: ${SYZYGY:-none}"

if [ "${1:-play}" = play ] || [ "$1" = upgrade ]; then
  if [ -z "$LICHESS_BOT_TOKEN" ]; then
    echo "Set the token of the BOT account in LICHESS_BOT_TOKEN (tools/lichess/.env with Docker Compose)" >&2
    exit 1
  fi
fi

case "${1:-play}" in
  play)
    exec python snaky_bot.py --config config.yml --disable_auto_logging
    ;;
  upgrade)
    exec python snaky_bot.py --config config.yml --disable_auto_logging -u
    ;;
  challenge)
    shift
    exec python /snaky-tools/challenge.py "$@"
    ;;
  test)
    # The test uses the mocked lichess.org of the tests of lichess-bot, so it runs inside its test_bot package
    cp /snaky-tools/test_offline.py test_bot/test_snaky_offline.py
    exec python -m pytest -q -s -p no:cacheprovider -W ignore::DeprecationWarning test_bot/test_snaky_offline.py
    ;;
  *)
    exec "$@"
    ;;
esac

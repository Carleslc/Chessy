#!/bin/sh
# SPRT test between two versions of the engine with fastchess: is the new version stronger?
# Each opening of the balanced book is played twice, with the colors swapped.
#
# ./sprt.sh <new> <base> [elo0] [elo1] [tc] [concurrency]
# ./sprt.sh tt phase3 0 10 8+0.08
#
# <new> and <base> are snapshots in engines/ (see snapshot.sh) or engine commands.
# Extra fastchess arguments can be given in the FASTCHESS_ARGS environment variable,
# for example FASTCHESS_ARGS="option.Threads=4" applies to both engines (1 thread by default, as many games run at once).
# The maximum number of rounds (2 games each) is given by the ROUNDS environment variable (default: 10000).
# Options of each engine: NEW_ARGS and BASE_ARGS, with names NEW_NAME and BASE_NAME (to compare styles of the same version):
# NEW_NAME=space NEW_ARGS=option.Style=Space BASE_NAME=material BASE_ARGS=option.Style=Material ./sprt.sh phase5 phase5
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
engine() { if [ -d "$DIR/engines/$1" ]; then echo "$DIR/engines/$1/bin/chessy"; else echo "$1"; fi; }
NEW=${1:?Usage: ./sprt.sh <new> <base> [elo0] [elo1] [tc] [concurrency]}
BASE=${2:?Usage: ./sprt.sh <new> <base> [elo0] [elo1] [tc] [concurrency]}
ELO0=${3:-0}
ELO1=${4:-10}
TC=${5:-8+0.08}
CONCURRENCY=${6:-10}
mkdir -p "$DIR/games"
fastchess \
  -engine cmd="$(engine "$NEW")" name="${NEW_NAME:-$NEW}" $NEW_ARGS \
  -engine cmd="$(engine "$BASE")" name="${BASE_NAME:-$BASE}" $BASE_ARGS \
  -each tc="$TC" option.Threads=1 $FASTCHESS_ARGS \
  -openings file="$DIR/openings/balanced.epd" format=epd order=random \
  -repeat -rounds "${ROUNDS:-10000}" -concurrency "$CONCURRENCY" \
  -sprt elo0="$ELO0" elo1="$ELO1" alpha=0.05 beta=0.1 \
  -pgnout file="$DIR/games/sprt-${NEW_NAME:-$NEW}-vs-${BASE_NAME:-$BASE}.pgn" \
  -config outname="$DIR/games/fastchess-${NEW_NAME:-$NEW}-vs-${BASE_NAME:-$BASE}.json"

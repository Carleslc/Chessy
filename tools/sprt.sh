#!/bin/sh
# SPRT test between two versions of the engine with fastchess: is the new version stronger?
# Each opening of the balanced book is played twice, with the colors swapped.
#
# ./sprt.sh <new> <base> [elo0] [elo1] [tc] [concurrency]
# ./sprt.sh tt phase3 0 10 8+0.08
#
# <new> and <base> are snapshots in engines/ (see snapshot.sh) or engine commands.
# Extra fastchess arguments can be given in the FASTCHESS_ARGS environment variable,
# for example FASTCHESS_ARGS="option.Threads=4" applies to both engines.
# The maximum number of rounds (2 games each) is given by the ROUNDS environment variable (default: 10000).
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
  -engine cmd="$(engine "$NEW")" name="$NEW" \
  -engine cmd="$(engine "$BASE")" name="$BASE" \
  -each tc="$TC" $FASTCHESS_ARGS \
  -openings file="$DIR/openings/balanced.epd" format=epd order=random \
  -repeat -rounds "${ROUNDS:-10000}" -concurrency "$CONCURRENCY" \
  -sprt elo0="$ELO0" elo1="$ELO1" alpha=0.05 beta=0.1 \
  -pgnout file="$DIR/games/sprt-$NEW-vs-$BASE.pgn" \
  -config outname="$DIR/games/fastchess-$NEW-vs-$BASE.json"

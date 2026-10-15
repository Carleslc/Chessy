#!/bin/sh
# Copy the current build of the engine to engines/<name>, to test it against other versions
# while the engine keeps changing.
#
# ./snapshot.sh tt
set -e
NAME=${1:?Usage: ./snapshot.sh <name>}
DIR=$(cd "$(dirname "$0")" && pwd)
(cd "$DIR/../engine" && ./gradlew -q installDist)
rm -rf "$DIR/engines/$NAME"
mkdir -p "$DIR/engines"
cp -R "$DIR/../engine/build/install/snaky" "$DIR/engines/$NAME"
echo "$DIR/engines/$NAME/bin/snaky"

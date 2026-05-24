#!/bin/bash
# Download/update EasyList and EasyPrivacy filter lists.
# Run periodically or before release builds.
# Lists are downloaded to the assets directory for bundling,
# AND to the SDK's data directory for runtime updates.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LISTS_DIR="$SCRIPT_DIR/filterlists"

mkdir -p "$LISTS_DIR"

echo "Downloading EasyList..."
curl -sL "https://easylist.to/easylist/easylist.txt" -o "$LISTS_DIR/easylist.txt"
echo "  $(wc -l < "$LISTS_DIR/easylist.txt") lines"

echo "Downloading EasyPrivacy..."
curl -sL "https://easylist.to/easylist/easyprivacy.txt" -o "$LISTS_DIR/easyprivacy.txt"
echo "  $(wc -l < "$LISTS_DIR/easyprivacy.txt") lines"

echo "Done. Lists saved to $LISTS_DIR/"
echo "Rules: $(grep -c '^||' "$LISTS_DIR/easylist.txt") EasyList, $(grep -c '^||' "$LISTS_DIR/easyprivacy.txt") EasyPrivacy"

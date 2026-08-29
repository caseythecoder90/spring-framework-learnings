#!/usr/bin/env bash
#
# Stage the static site into target/site by copying site/ and the markdown notes together.
# The pages fetch notes/<name>.md over HTTP, so this needs a server, not file://.
#
#   ./tools/build-site.sh                 # build only
#   ./tools/build-site.sh --serve         # build, then serve on http://localhost:8099
#   ./tools/build-site.sh --serve 9000    # ...on a port of your choosing
#
set -euo pipefail

OUT="target/site"

rm -rf "$OUT"
mkdir -p "$OUT/notes"
cp -R site/. "$OUT/"
cp docs/*.md "$OUT/notes/"

echo "built -> $OUT"

if [ "${1:-}" = "--serve" ]; then
  PORT="${2:-8099}"
  echo "serving http://localhost:${PORT}  (ctrl-c to stop)"
  if command -v python3 >/dev/null 2>&1; then
    python3 -m http.server "$PORT" --directory "$OUT"
  else
    python -m http.server "$PORT" --directory "$OUT"
  fi
fi

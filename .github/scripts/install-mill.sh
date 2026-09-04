#!/usr/bin/env bash
# Pre-install the exact Mill distribution the ./mill launcher would download, verified against the
# SHA-256 pinned in .github/mill-dist.sha256. The launcher skips its own unverified download
# whenever the final path is non-empty (`[ ! -s "$MILL" ]`), so after this step every `./mill`
# execs verified bytes — and a restored cache whose Mill binary does not match the pin fails the
# job instead of running.
#
# Runs as the last step of .github/actions/setup-build in every CI job. Portable to bash 3.2
# (macOS /bin/bash) — no mapfile, no associative arrays.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SUMS="$ROOT/.github/mill-dist.sha256"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# The launcher's dry-run mode prints exactly two lines — the download URL and the final path — and
# exits 0 even when the file already exists.
DRY="$(MILL_TEST_DRY_RUN_LAUNCHER_SCRIPT=1 ./mill)"
LINES="$(printf '%s\n' "$DRY" | wc -l | tr -d ' ')"
URL="$(printf '%s\n' "$DRY" | sed -n 1p)"
DEST="$(printf '%s\n' "$DRY" | sed -n 2p)"
if [ "$LINES" != "2" ] || [ -z "$URL" ] || [ -z "$DEST" ]; then
  echo "::error::install-mill: launcher dry-run printed $LINES line(s), expected URL then path — the mill launcher format changed; re-check .github/scripts/install-mill.sh"
  printf '%s\n' "$DRY"
  exit 1
fi
NAME="$(basename "$URL")"
EXPECTED="$(awk -v n="$NAME" '$1 !~ /^#/ && $2 == n { print $1 }' "$SUMS")"
if [ -z "$EXPECTED" ]; then
  echo "::error::install-mill: no pinned SHA-256 for $NAME in .github/mill-dist.sha256. After bumping .mill-version: curl -fsSL $URL -o /tmp/m; test \"\$(curl -fsSL $URL.sha1)\" = \"\$(shasum -a 1 /tmp/m | cut -d' ' -f1)\"; shasum -a 256 /tmp/m — then add the line '<sha256>  $NAME'."
  exit 1
fi

if [ -s "$DEST" ]; then
  ACTUAL="$(sha256_of "$DEST")"
  if [ "$ACTUAL" = "$EXPECTED" ]; then
    echo "install-mill: $DEST matches pinned SHA-256 ($EXPECTED)"
    exit 0
  fi
  # A mismatch is a poisoned cache or a wrong pin — both need a human. An exact-key cache hit is
  # never re-saved, so the entry persists until deleted.
  echo "::error::install-mill: $DEST (restored from cache) has SHA-256 $ACTUAL, pinned $EXPECTED — refusing to run it. A cache hit is never re-saved, so this entry persists: investigate, then purge it with 'gh cache list --key ${MILL_CACHE_KEY_HINT:-<os>-mill-<prefix>-}' and 'gh cache delete <id>' (or 'gh cache delete --all')."
  exit 1
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
echo "install-mill: downloading $URL"
curl -fsSL --retry 3 --retry-all-errors -o "$TMP" "$URL"
ACTUAL="$(sha256_of "$TMP")"
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "::error::install-mill: SHA-256 mismatch for $URL: got $ACTUAL, pinned $EXPECTED"
  exit 1
fi
chmod +x "$TMP"
mkdir -p "$(dirname "$DEST")"
mv "$TMP" "$DEST"
trap - EXIT
echo "install-mill: installed verified Mill at $DEST"

#!/usr/bin/env bash
# Prints the next release version from Conventional Commits since the latest v* tag:
#   breaking change ("type!:" or "BREAKING CHANGE") -> major, feat -> minor, anything else -> patch.
# With no tag yet the first release is 0.1.0. Prints nothing when HEAD is already tagged.
set -euo pipefail

last=$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || true)
if [[ -z $last ]]; then
  echo "0.1.0"
  exit 0
fi
if [[ -z $(git rev-list "$last..HEAD") ]]; then
  exit 0
fi

IFS=. read -r major minor patch <<<"${last#v}"
log=$(git log --format='%B' "$last..HEAD")
if grep -qE '^[a-z]+(\([^)]*\))?!:|^BREAKING[ -]CHANGE:' <<<"$log"; then
  echo "$((major + 1)).0.0"
elif grep -qE '^feat(\([^)]*\))?:' <<<"$log"; then
  echo "$major.$((minor + 1)).0"
else
  echo "$major.$minor.$((patch + 1))"
fi

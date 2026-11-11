#!/usr/bin/env bash
# Checks the Play listing text: length limits and banned words.
# Run from anywhere: scripts/check-listing.sh [listing-dir]
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
dir="${1:-$root/androidApp/src/main/play/listings}"
lang="$dir/en-US"
fail=0

check_len() { # file max
  local f="$lang/$1" max="$2" n
  if [ ! -f "$f" ]; then echo "FAIL: $f is missing"; fail=1; return; fi
  n=$(wc -m < "$f" | tr -d ' ')
  if [ "$n" -gt "$max" ]; then
    echo "FAIL: $1 is $n characters, limit is $max"; fail=1
  else
    echo "ok: $1 is $n characters (limit $max)"
  fi
}

check_len title.txt 30
check_len short-description.txt 80
check_len full-description.txt 4000

if grep -rniE 'adversarial|solves|60 ?fps|strictly|vibrant|comprehensive|deep system|seamless|blazing|tri-format|powerful|effortless' "$dir"; then
  echo "FAIL: banned words found in $dir"; fail=1
else
  echo "ok: no banned words"
fi

exit "$fail"

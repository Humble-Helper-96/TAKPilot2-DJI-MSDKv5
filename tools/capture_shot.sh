#!/usr/bin/env bash
#
# Capture one screenshot from the controller for the training material.
#
# The operator navigates to the screen. This script only captures it. It does not touch the
# UI, because a screen driven by adb taps is not the screen a pilot sees.
#
# ⚠ THE SHOTS DO NOT GO IN THIS REPOSITORY. This repository is public on GitHub, and a
# screenshot of the flight screen carries the TAK server address, the callsign, the channel
# names and a real position. The default output is the training directory in the parent
# UAS_Apps tree, which is a whitelist repository with no remote: a file that is not on the
# whitelist cannot be committed by accident. Override it with TRAINING_DIR if you must.
#
# The pictures sit in two directories:
#   screenshots/raw    what the controller gave, never changed
#   screenshots/final  the joined and the cut pictures, which the course uses
# Keep them apart. An edit that writes over its own source costs a trip to the controller,
# and a screen in flight cannot be gone back to.
#
# Each capture writes a row to manifest.tsv. The row keeps the versionCode of the app that
# made the shot, thus a shot from an old build can be found again after the UI changes. A
# picture you cannot date is a picture you cannot trust.
#
# Usage:
#   tools/capture_shot.sh [-f] [-t TIER] SLUG ["what the screen shows"]
#   tools/capture_shot.sh --list
#
#   -f        Replace a shot that exists. Without it the script refuses, so a good capture
#             is not lost by a repeated command.
#   -t TIER   a = cold, no aircraft.  b = aircraft on, on the ground.  c = in flight.
#
# Example:
#   tools/capture_shot.sh -t a preflight-aircraft-settings "Aircraft Settings, all fields"

set -euo pipefail

PKG="com.anchortak.takpilot2djiv5"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRAINING_DIR="${TRAINING_DIR:-$SCRIPT_DIR/../../training}"
# Captures are RAW and stay raw. Anything joined or cut goes to screenshots/final, thus a
# picture that took a trip to the controller cannot be lost to an edit.
SHOT_DIR="$TRAINING_DIR/screenshots/raw"
MANIFEST="$TRAINING_DIR/manifest.tsv"

FORCE=0
TIER="-"
while [ $# -gt 0 ]; do
    case "$1" in
        -f) FORCE=1; shift ;;
        -t) TIER="${2:?-t needs a tier}"; shift 2 ;;
        --list) LIST=1; shift ;;
        -*) echo "unknown option: $1" >&2; exit 1 ;;
        *) break ;;
    esac
done

mkdir -p "$SHOT_DIR"
[ -f "$MANIFEST" ] || printf 'slug\ttier\tcaptured\tpackage\tversionCode\tdescription\n' > "$MANIFEST"

if [ "${LIST:-0}" = 1 ]; then
    column -t -s $'\t' "$MANIFEST"
    echo
    echo "$(( $(wc -l < "$MANIFEST") - 1 )) shots in $SHOT_DIR"
    exit 0
fi

SLUG="${1:?Give a slug, for example preflight-aircraft-settings}"
DESC="${2:-}"
case "$SLUG" in
    *[!a-z0-9-]*) echo "The slug takes lower case letters, digits and '-' only: $SLUG" >&2; exit 1 ;;
esac

OUT="$SHOT_DIR/$SLUG.png"
if [ -f "$OUT" ] && [ "$FORCE" = 0 ]; then
    echo "$SLUG.png exists. Use -f to replace it." >&2
    exit 1
fi

# One device only. With two attached, adb picks one and you cannot tell which.
COUNT=$(adb devices | grep -cw device || true)
if [ "$COUNT" -ne 1 ]; then
    echo "Attach exactly one device. adb reports $COUNT." >&2
    adb devices -l >&2
    exit 1
fi

# Which app and which screen is in front. A comparison shot of DJI Pilot 2 is expected, thus
# this reports and does not refuse — but it must go in the manifest, so a Pilot 2 shot is
# never read as ours. The activity name identifies the screen, which the slug alone does not.
#
# ⚠ The name of the field changes with the Android version. The RC Plus 2 gives
# `mResumedActivity`; `topResumedActivity` is the newer one. Match either, or every row says
# "unknown" and the manifest tells you nothing.
FG=$(adb shell dumpsys activity activities \
     | grep -m1 -oP '(m|top)ResumedActivity:?.*?\{[^ ]* [^ ]* \K[^ }]+' || echo "unknown")
[ -n "$FG" ] || FG="unknown"
VC=$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -oP 'versionCode=\K[0-9]+' | head -1 || true)
[ -n "$VC" ] || VC="-"

adb exec-out screencap -p > "$OUT"

# Confirm the PNG from the file, not from the exit status: screencap can return 0 and write
# nothing when the screen is off or a secure window is in front.
if [ ! -s "$OUT" ] || [ "$(head -c8 "$OUT" | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]; then
    rm -f "$OUT"
    echo "No valid PNG came back. Is the screen on, and is the screen not a secure one?" >&2
    exit 1
fi

printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$SLUG" "$TIER" "$(date -Iseconds)" "$FG" "$VC" "$DESC" >> "$MANIFEST"

SIZE=$(adb exec-out wm size | grep -oP 'Physical size: \K.*' || echo "?")
echo "captured $SLUG.png  ($SIZE, front: $FG, versionCode $VC)"

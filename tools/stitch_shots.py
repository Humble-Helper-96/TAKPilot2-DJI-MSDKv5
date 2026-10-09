#!/usr/bin/env python3
"""Join the parts of a scrolled screen into one tall picture.

A section of Pre-Flight Setup is longer than the screen. The operator captures it in parts and
scrolls between them. This joins the parts.

The join is found from the pixels, not from the scroll distance, because the operator scrolls by
hand and no two scrolls are the same. The script slides each part against the one before it and
keeps the offset with the smallest difference. It then prints that difference, thus a bad join
is seen and not shipped.

⚠ THE HEADER AND THE FOOTER DO NOT SCROLL. The blue "Pre-Flight Setup" bar stays at the top and
the navigation bar stays at the bottom. Both are cut off the parts that follow the first one,
or each join repeats them down the picture.

Usage:
    tools/stitch_shots.py preflight-aircraft-settings
    tools/stitch_shots.py --header 240 --footer 55 preflight-tak-connection

It reads <slug>-1.png, <slug>-2.png ... from screenshots/raw and writes <slug>.png to
screenshots/final. The parts are not changed. Give --out to write somewhere else.
"""

import argparse
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DIR = os.environ.get(
    "TRAINING_DIR", os.path.join(HERE, "..", "..", "training")
)

# A join under this mean difference (0-255 per channel) is good. Above it, the parts probably do
# not overlap at all — the scroll went too far and a band of the screen was never captured.
GOOD_JOIN = 6.0


def find_overlap(top, bottom, min_overlap=80):
    """Return how many rows at the bottom of `top` repeat at the top of `bottom`."""
    ta, ba = np.asarray(top, dtype=np.int16), np.asarray(bottom, dtype=np.int16)
    height = min(ta.shape[0], ba.shape[0])
    best, best_score = 0, float("inf")
    # Try every overlap from a large one down to a small one. A tall overlap that matches is
    # more trustworthy than a short one, thus ties keep the taller.
    for k in range(height - 1, min_overlap - 1, -1):
        score = float(np.abs(ta[-k:] - ba[:k]).mean())
        if score < best_score:
            best, best_score = k, score
    return best, best_score


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("slug")
    ap.add_argument("--header", type=int, default=240, help="rows of fixed header to cut")
    ap.add_argument("--footer", type=int, default=55, help="rows of fixed footer to cut")
    ap.add_argument("--raw", default=os.path.join(DEFAULT_DIR, "screenshots", "raw"))
    ap.add_argument("--final", default=os.path.join(DEFAULT_DIR, "screenshots", "final"))
    ap.add_argument("--out")
    args = ap.parse_args()

    parts = []
    for n in range(1, 21):
        path = os.path.join(args.raw, f"{args.slug}-{n}.png")
        if not os.path.exists(path):
            break
        parts.append(Image.open(path).convert("RGB"))

    if len(parts) < 2:
        sys.exit(f"Need at least {args.slug}-1.png and {args.slug}-2.png in {args.raw}")

    widths = {p.width for p in parts}
    if len(widths) != 1:
        sys.exit(f"The parts are not the same width: {widths}")

    # The first part keeps its header. Every part loses its footer except the last one, which
    # keeps it so the finished picture ends the way the screen ends.
    canvas = parts[0].crop((0, 0, parts[0].width, parts[0].height - args.footer))
    worst = 0.0

    for n, part in enumerate(parts[1:], start=2):
        body = part.crop((0, args.header, part.width, part.height - args.footer))
        overlap, score = find_overlap(canvas, body)
        worst = max(worst, score)
        flag = "" if score <= GOOD_JOIN else "  ⚠ CHECK THIS JOIN"
        print(f"  part {n}: overlap {overlap} px, difference {score:.2f}{flag}")

        new = body.crop((0, overlap, body.width, body.height))
        joined = Image.new("RGB", (canvas.width, canvas.height + new.height))
        joined.paste(canvas, (0, 0))
        joined.paste(new, (0, canvas.height))
        canvas = joined

    tail = parts[-1].crop(
        (0, parts[-1].height - args.footer, parts[-1].width, parts[-1].height)
    )
    final = Image.new("RGB", (canvas.width, canvas.height + args.footer))
    final.paste(canvas, (0, 0))
    final.paste(tail, (0, canvas.height))

    os.makedirs(args.final, exist_ok=True)
    out = args.out or os.path.join(args.final, f"{args.slug}.png")
    final.save(out)
    print(f"wrote {out}  ({final.width}x{final.height} from {len(parts)} parts)")
    if worst > GOOD_JOIN:
        sys.exit("A join is doubtful. Look at the picture before you use it.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Cut a picture down to the part that teaches.

Two jobs. It removes the start of the next section when one capture holds more than one
section. It also cuts a close view out of a joined picture, thus a detail does not need a
second trip to the controller.

⚠ IT NEVER WRITES TO screenshots/raw. The source is looked for in raw first, then in final,
and the result always goes to final. An early version of this script wrote over its own
source and two raw captures were lost. A picture from the controller is expensive: a screen
in flight cannot be gone back to.

Give no box at all to copy a capture to final unchanged. A screen that needs no edit is
still a picture the course uses, thus it belongs in final with the others.

Usage:
    tools/crop_shot.py preflight-dted --bottom 812
    tools/crop_shot.py preflight-uasfm
    tools/crop_shot.py preflight-aircraft-settings --top 1180 --bottom 1330 \
        --out preflight-obstacle-line
"""

import argparse
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DIR = os.environ.get("TRAINING_DIR", os.path.join(HERE, "..", "..", "training"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("slug")
    ap.add_argument("--top", type=int, default=0)
    ap.add_argument("--bottom", type=int, help="last row to keep (default: the full height)")
    ap.add_argument("--left", type=int, default=0)
    ap.add_argument("--right", type=int, help="last column to keep (default: the full width)")
    ap.add_argument("--raw", default=os.path.join(DEFAULT_DIR, "screenshots", "raw"))
    ap.add_argument("--final", default=os.path.join(DEFAULT_DIR, "screenshots", "final"))
    ap.add_argument("--out", help="slug to write in final (default: the same slug)")
    args = ap.parse_args()

    # raw first: a slug that is in both is a capture and its finished picture, and a crop of
    # the capture is almost always what is wanted.
    for folder in (args.raw, args.final):
        src = os.path.join(folder, f"{args.slug}.png")
        if os.path.exists(src):
            break
    else:
        sys.exit(f"No {args.slug}.png in {args.raw} or {args.final}")

    im = Image.open(src)
    box = (
        args.left,
        args.top,
        args.right if args.right is not None else im.width,
        args.bottom if args.bottom is not None else im.height,
    )
    if box[2] <= box[0] or box[3] <= box[1]:
        sys.exit(f"The box is empty: {box}")

    os.makedirs(args.final, exist_ok=True)
    out = os.path.join(args.final, f"{args.out or args.slug}.png")
    if os.path.abspath(out) == os.path.abspath(src) and box == (0, 0, im.width, im.height):
        sys.exit(f"That would copy {out} onto itself. Give --out, or give a box.")

    im.crop(box).save(out)
    kept = f"{box[2] - box[0]}x{box[3] - box[1]}"
    print(f"wrote {out}  ({kept} from {im.width}x{im.height} in {os.path.basename(folder)})")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Measures the flight screen's action bar from a screenshot, and says whether it FITS.

WHY THIS EXISTS: the action bar has overflowed twice. versionCode 7 shipped with the record
toggle cut off at the screen edge, and on 2026-08-20 adding the IR and lights pills squeezed
REC from 74dp to 59.6dp. Both times the failure looked like a slightly tight bar rather than
a defect, because a LinearLayout does not complain when it runs out of width — it silently
compresses its last child. Arithmetic on the layout file misses it too: two children are
wrap_content, and the weighted spacer hides the deficit until it reaches zero.

So the only honest check is to measure the rendered pixels. A squeezed last child is proof
of overflow: REC is declared 74dp, thus any rendered width below that is the bar telling you
it did not fit.

USAGE
    adb exec-out screencap -p > /tmp/flight.png
    python3 tools/measure_toolbar.py /tmp/flight.png

The device must be ON THE FLIGHT SCREEN. uiautomator cannot be used instead: the HUD ticks
twice a second, so the dump never reaches idle state and the command fails.
"""
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("needs pillow:  pip install pillow")

# The RC Plus 2 is 768dp wide. REC is declared 74dp in the layout; if it renders
# narrower than this, the bar overflowed and the LinearLayout compressed it.
SCREEN_DP = 768.0
REC_DECLARED_DP = 74.0
TOLERANCE_DP = 2.0


def is_toolbar_bg(px):
    """True for the toolbar's blue (bg_toolbar.xml, #0D47A1 with a gradient to #0A3A80)."""
    r, g, b = px
    return b > 100 and b > r + 40 and g < 110 and r < 70


def main(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    dp = w / SCREEN_DP
    row = [im.getpixel((x, int(70 * h / 1200))) for x in range(w)]

    if not any(is_toolbar_bg(p) for p in row):
        sys.exit("no toolbar blue found — is the device on the flight screen?")

    # The last control on the bar is REC. Walk in from the right edge.
    x = w - 1
    while x > 0 and is_toolbar_bg(row[x]):
        x -= 1
    right = x
    gap = 0
    left = right
    for xx in range(right, 0, -1):
        if is_toolbar_bg(row[xx]):
            gap += 1
            if gap / dp >= 6:      # a real gap between controls, not a glyph hole
                left = xx + gap
                break
        else:
            gap = 0

    rec_dp = (right - left) / dp
    margin_dp = (w - 1 - right) / dp

    # Every background run wide enough to be slack rather than letter spacing.
    runs, start = [], None
    for i, p in enumerate(row):
        if is_toolbar_bg(p):
            if start is None:
                start = i
        else:
            if start is not None and (i - start) / dp >= 15:
                runs.append((start, i))
            start = None
    slack = max(((b - a) / dp for a, b in runs), default=0.0)

    print(f"  screenshot   {w}x{h}  ({dp:.2f} px/dp)")
    print(f"  REC rendered {rec_dp:.1f}dp   (declared {REC_DECLARED_DP:.0f}dp)")
    print(f"  right margin {margin_dp:.1f}dp")
    print(f"  largest gap  {slack:.1f}dp")

    if rec_dp < REC_DECLARED_DP - TOLERANCE_DP:
        short = REC_DECLARED_DP - rec_dp
        print(f"\n  ✗ OVERFLOW — REC is {short:.1f}dp short. The bar does not fit;")
        print(f"    free at least {short:.0f}dp before shipping this layout.")
        return 1
    print("\n  ✓ FITS — the last control renders at its declared width.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))

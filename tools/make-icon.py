#!/usr/bin/env python3
"""Regenerate res/mipmap-*/ic_launcher_foreground.png.

The launcher icon is an adaptive icon (see res/mipmap-anydpi-v26/ic_launcher.xml): a flat
background colour with this PNG on top, so the launcher supplies the shape. The canvas is
108dp at every density.

    python3 tools/make-icon.py            # rewrite the five PNGs
    python3 tools/make-icon.py --preview  # also write tools/icon-preview.png

The artwork is the app's own pointer, recoloured yellow. It is drawn on an 864px master and
downsampled, so all five buckets come from one design instead of five hand-tuned ones, and it
is the upstream pointer at its original size: that geometry is known to sit inside the safe
zone, so there is no reason to invent a new one.
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The pristine white arrow, kept beside this script so re-running it cannot compound: the
# generated icons are written into res/, which is else what the source would be read from.
SRC = os.path.join(ROOT, "tools/pointer.png")
SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

MASTER = 864                      # 108dp at 8px/dp; every bucket is a downsample of this
YELLOW = (255, 212, 0, 255)

# The safe zone: 66dp diameter, centred, in master pixels. Anything outside it can be
# clipped by a circular launcher mask, so the artwork has to stay well inside.
SAFE_R = 33 * 8                   # 264


def master() -> Image.Image:
    """The recoloured pointer, centred on an 864px master."""
    arrow = Image.open(SRC).convert("RGBA")
    recoloured = Image.new("RGBA", arrow.size, YELLOW)
    recoloured.putalpha(arrow.getchannel("A"))

    # SRC is a 108dp canvas with the arrow already centred in it, so scaling the whole
    # canvas keeps the arrow centred and at its original proportion of the icon.
    im = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    im.alpha_composite(recoloured.resize((MASTER, MASTER), Image.LANCZOS))

    # Fail loudly rather than shipping an icon that a round mask will clip.
    ink = im.getbbox()
    worst = max(
        (x - MASTER // 2) ** 2 + (y - MASTER // 2) ** 2
        for x in (ink[0], ink[2]) for y in (ink[1], ink[3])
    ) ** 0.5
    if worst > SAFE_R:
        raise SystemExit("ink reaches %.0fpx from centre, past the %dpx safe radius" % (worst, SAFE_R))
    return im


def safe(m: Image.Image) -> Image.Image:
    """Circle-mask the master the way a round launcher would, for previewing only."""
    c = MASTER // 2
    disc = Image.new("L", m.size, 0)
    ImageDraw.Draw(disc).ellipse((c - SAFE_R, c - SAFE_R, c + SAFE_R, c + SAFE_R), 255)
    out = Image.new("RGBA", m.size, (0, 0, 0, 0))
    out.paste(m, (0, 0), disc)
    return out


def main() -> int:
    m = master()
    for bucket, px in SIZES.items():
        path = os.path.join(ROOT, "res", "mipmap-%s" % bucket, "ic_launcher_foreground.png")
        m.resize((px, px), Image.LANCZOS).save(path)
        print("wrote %s (%dx%d)" % (os.path.relpath(path, ROOT), px, px))

    if "--preview" in sys.argv:
        # What the launcher actually does with it: on the background colour, then masked.
        bg = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 255))
        bg.alpha_composite(m)
        cut = safe(bg)
        cells = []
        for px, masked in ((108, True), (48, True), (48, False)):
            cell = (cut if masked else bg).resize((px, px), Image.LANCZOS)
            if px == 48:
                cell = cell.resize((144, 144), Image.NEAREST)
            cells.append(cell)
        pad = 24
        w = sum(c.width for c in cells) + pad * (len(cells) + 1)
        h = max(c.height for c in cells) + pad * 2
        sheet = Image.new("RGBA", (w, h), (32, 32, 38, 255))
        x = pad
        for cell in cells:
            sheet.alpha_composite(cell, (x, (h - cell.height) // 2))
            x += cell.width + pad
        path = os.path.join(ROOT, "tools", "icon-preview.png")
        sheet.convert("RGB").save(path)
        print("wrote %s" % os.path.relpath(path, ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

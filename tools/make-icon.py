#!/usr/bin/env python3
"""Regenerate res/mipmap-*/ic_launcher_foreground.png.

The launcher icon is an adaptive icon (see res/mipmap-anydpi-v26/ic_launcher.xml): a flat
background colour with this PNG on top, so the launcher supplies the shape. The canvas is
108dp at every density; the part that survives every mask is the central 66dp circle, so
everything drawn here has to stay inside it.

    python3 tools/make-icon.py            # rewrite the five PNGs
    python3 tools/make-icon.py --preview  # also write tools/icon-preview.png

The artwork is the app's own pointer, recoloured, with "Lite" beneath it. Everything is
drawn on a 864px master and downsampled, so all five buckets come from one design instead
of five hand-tuned ones.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The pristine white arrow, kept beside this script so re-running it cannot compound: the
# generated icons are written into res/, which is else what the source would be read from.
SRC = os.path.join(ROOT, "tools/pointer.png")
FONT_PATH = "/system/fonts/Roboto-Regular.ttf"
SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

MASTER = 864                      # 108dp at 8px/dp; every bucket is a downsample of this
YELLOW = (255, 212, 0, 255)
LABEL = "lite"

# The safe zone: 66dp diameter, centred, in master pixels.
SAFE_R = 33 * 8                   # 264


def _fit(im: Image.Image, height: int) -> Image.Image:
    """Scale so the ink is exactly `height` tall, preserving aspect."""
    return im.resize((round(im.width * height / im.height), height), Image.LANCZOS)


def master() -> Image.Image:
    """Draw the icon once, at 864px, on transparency.

    Both elements are cropped to their ink before being placed, and positioned by that ink:
    the arrow's source PNG has 100-odd px of empty margin around it, so placing the *image*
    instead of the drawing puts the arrow a third of the way down the canvas and straight
    through the label.
    """
    im = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))

    # --- the pointer, recoloured, in the upper band ---------------------------------
    arrow = Image.open(SRC).convert("RGBA")
    recoloured = Image.new("RGBA", arrow.size, YELLOW)
    recoloured.putalpha(arrow.getchannel("A"))
    arrow = _fit(recoloured.crop(recoloured.getbbox()), 175)

    # --- "lite", in the lower band ---------------------------------------------------
    # A stroke rather than a bold face: only Roboto-Regular ships on this device, and at
    # 48px the extra weight is the difference between readable and a smudge. getbbox() is
    # ink-only, which is what makes the two bands line up predictably.
    font = ImageFont.truetype(FONT_PATH, 135)
    tmp = Image.new("RGBA", (900, 500), (0, 0, 0, 0))
    ImageDraw.Draw(tmp).text((30, 30), LABEL, font=font, fill=YELLOW,
                             stroke_width=9, stroke_fill=YELLOW)
    label = _fit(tmp.crop(tmp.getbbox()), 105)

    cx = MASTER // 2
    im.alpha_composite(arrow, (cx - arrow.width // 2, 260))
    im.alpha_composite(label, (cx - label.width // 2, 470))
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
        for px, label in ((108, "108 round"), (48, "48 round (x3)"), (48, "48 square (x3)")):
            src = cut if "round" in label else bg
            cell = src.resize((px, px), Image.LANCZOS)
            if px == 48:
                cell = cell.resize((144, 144), Image.NEAREST)
            cells.append((cell, label))
        pad = 24
        w = sum(c.width for c, _ in cells) + pad * (len(cells) + 1)
        h = max(c.height for c, _ in cells) + pad * 2
        sheet = Image.new("RGBA", (w, h), (32, 32, 38, 255))
        x = pad
        for cell, _ in cells:
            sheet.alpha_composite(cell, (x, (h - cell.height) // 2))
            x += cell.width + pad
        path = os.path.join(ROOT, "tools", "icon-preview.png")
        sheet.convert("RGB").save(path)
        print("wrote %s" % os.path.relpath(path, ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

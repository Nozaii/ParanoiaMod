"""Assemble les captures prev_<id>_<n>.png (run/screenshots) en bandes animées 320x180 x 6 images pour le jar.

Usage : python -I tools/make_previews.py
"""
import glob
import os
import re

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "run", "screenshots")
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "aurora", "textures", "preview")
W, H, FRAMES = 384, 216, 6


def crop(path):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    # presque toute l'image (92 %), centrée : l'effet reste entier, les bords de la fenêtre sont retirés
    cw = int(w * 0.92)
    ch = int(cw * 9 / 16)
    x0 = (w - cw) // 2
    y0 = max(0, (h - ch) // 2)
    return img.crop((x0, y0, x0 + cw, y0 + ch)).resize((W, H), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    mods = {}
    for path in glob.glob(os.path.join(SRC, "prev_*_*.png")):
        m = re.match(r"prev_(.+)_(\d+)\.png$", os.path.basename(path))
        if m:
            mods.setdefault(m.group(1), {})[int(m.group(2))] = path
    n = 0
    for mod, frames in sorted(mods.items()):
        strip = Image.new("RGB", (W, H * FRAMES))
        last = None
        for i in range(FRAMES):
            if i in frames:
                last = crop(frames[i])
            if last is not None:
                strip.paste(last, (0, i * H))
        # palette 256 couleurs : bien plus léger dans le jar, invisible à cette taille
        strip.quantize(256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG).save(os.path.join(OUT, mod + ".png"), optimize=True)
        n += 1
    print(f"{n} aperçus animés -> {OUT}")


if __name__ == "__main__":
    main()

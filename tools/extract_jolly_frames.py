"""Cut the Jollybot preview gif into per-frame PNGs for the watch app.

Run:  python3 tools/extract_jolly_frames.py
Reads  assets/charm/_reference/jolly_reference.gif
Writes watch/src/main/assets/jolly/jolly_000.png ... (cropped to the area the character uses, 8-bit palette)

Frame map (checked by counting the pink hearts per frame):
  0-12   calm            (open dot eyes)
  13-49  happy           (arms up, hearts)
  50-74  back to calm    (includes a blink)
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "assets/charm/_reference/jolly_reference.gif"
OUT = ROOT / "watch/src/main/assets/jolly"
CROP = (12, 0, 312, 296)  # union of non-black pixels over all frames is (15, 0, 310, 294)

OUT.mkdir(parents=True, exist_ok=True)
for old in OUT.glob("jolly_*.png"):
    old.unlink()
gif = Image.open(SRC)
for i in range(gif.n_frames):
    gif.seek(i)
    frame = gif.convert("RGB").crop(CROP).quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    frame.save(OUT / f"jolly_{i:03d}.png", optimize=True)
print(gif.n_frames, "frames,", CROP[2] - CROP[0], "x", CROP[3] - CROP[1])

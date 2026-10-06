"""Extract the Jollybot animation at its native size from Meta's happy_anim.c.

Run:  python3 tools/extract_jolly_native.py
Reads  reference/muse-gadget-sdk/esp32/avatar/happy_anim.c  (56x59 px, 75 frames, 23-colour palette,
       40 ms per frame; the palette is RGB565 byte-swapped, with the real colour in each line's comment)
Writes watch/src/main/assets/jolly/jolly_sheet.png  (one 8-bit PNG: 15 columns x 5 rows of frames)
"""
import re
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "reference/muse-gadget-sdk/esp32/avatar/happy_anim.c"
OUT = ROOT / "watch/src/main/assets/jolly"
W, H, FRAMES, COLS = 56, 59, 75, 15

text = SRC.read_text(encoding="utf-8")
pal_block = re.search(r"happy_anim_palette\[\d+\]\s*=\s*\{(.*?)\};", text, re.S).group(1)
palette = [tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for h in re.findall(r"//\s*#([0-9a-fA-F]{6})", pal_block)]
data_block = re.search(r"happy_anim_frames[^=]*=\s*\{(.*)\};", text, re.S).group(1)
data = bytes(int(v, 0) for v in re.findall(r"0x[0-9a-fA-F]+|\b\d+\b", data_block))
assert len(data) == W * H * FRAMES, (len(data), W * H * FRAMES)

rows = (FRAMES + COLS - 1) // COLS
sheet = Image.new("P", (COLS * W, rows * H), 0)
flat = [c for rgb in palette for c in rgb] + [0] * (768 - 3 * len(palette))
sheet.putpalette(flat)
for f in range(FRAMES):
    tile = Image.frombytes("P", (W, H), data[f * W * H:(f + 1) * W * H])
    sheet.paste(tile, ((f % COLS) * W, (f // COLS) * H))

OUT.mkdir(parents=True, exist_ok=True)
for old in OUT.glob("jolly_*.png"):
    old.unlink()
sheet.save(OUT / "jolly_sheet.png", optimize=True)
print(f"{FRAMES} frames {W}x{H}, {len(palette)} colours, sheet {sheet.size}")

#!/usr/bin/env python3
"""Package the approved Icon.png exactly; resize/pad only, with no generative changes."""
from pathlib import Path
import shutil
import sys
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "artwork/Icon.png"
if len(sys.argv) == 2:
    SOURCE.parent.mkdir(parents=True, exist_ok=True)
    incoming = Path(sys.argv[1]).resolve()
    if incoming != SOURCE:
        shutil.copyfile(incoming, SOURCE)
image = Image.open(SOURCE).convert("RGBA")
assert image.width == image.height, "Use the approved square source without cropping"
RES = ROOT / "app/src/main/res"
background = image.getpixel((0, 0))[:3] + (255,)
densities = {"ldpi": .75, "mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for density, scale in densities.items():
    folder = RES / ("mipmap-" + density)
    folder.mkdir(parents=True, exist_ok=True)
    side = round(48 * scale)
    image.resize((side, side), Image.Resampling.LANCZOS).save(folder / "ic_launcher.png", optimize=True)
    # Adaptive layers are 108dp; the unmodified image occupies a centered 64dp square.
    # Its character and MelKit wordmark stay inside the central safe region.
    canvas_side, art_side = round(108 * scale), round(64 * scale)
    foreground = Image.new("RGBA", (canvas_side, canvas_side))
    foreground.alpha_composite(image.resize((art_side, art_side), Image.Resampling.LANCZOS),
        ((canvas_side - art_side) // 2, (canvas_side - art_side) // 2))
    foreground.save(folder / "ic_launcher_foreground.png", optimize=True)
    # API 25 round fallback uses the same artwork positioning as the adaptive viewport.
    round_icon = Image.new("RGBA", (side, side), background)
    round_art = round(side * 64 / 72)
    round_icon.alpha_composite(image.resize((round_art, round_art), Image.Resampling.LANCZOS),
        ((side - round_art) // 2, (side - round_art) // 2))
    mask = Image.new("L", (side, side))
    ImageDraw.Draw(mask).ellipse((0, 0, side - 1, side - 1), fill=255)
    round_icon.putalpha(mask)
    round_icon.save(folder / "ic_launcher_round.png", optimize=True)

adaptive = RES / "mipmap-anydpi-v26"
adaptive.mkdir(parents=True, exist_ok=True)
xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
for name in ("ic_launcher", "ic_launcher_round"):
    (adaptive / (name + ".xml")).write_text(xml)
color = "#" + "".join(f"{value:02X}" for value in background[:3])
(RES / "values/launcher_icon.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">{color}</color>\n</resources>\n')
store = SOURCE.parent / "MelKit_Play_Icon_512.png"
image.resize((512, 512), Image.Resampling.LANCZOS).save(store, optimize=True)
assert store.stat().st_size <= 1024 * 1024
print("Packaged the approved source into six launcher densities, adaptive layers and 512px RGBA store icon.")

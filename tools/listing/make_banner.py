"""KMP Device Hub banner (1920x1080), in the same style as the KMP Dependency Hub one.

python3 tools/listing/make_banner.py idea-plugin/build/listing <device-screenshots-dir> dist/listing/banner.png
"""
import random
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

RENDERS, FOOTAGE, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
W, H = 1920, 1080
HELVETICA = "/System/Library/Fonts/HelveticaNeue.ttc"

def font(size, bold=False):
    # HelveticaNeue.ttc: 0 regular, 1 bold.
    return ImageFont.truetype(HELVETICA, size, index=1 if bold else 0)

def background():
    """Deep navy-violet with a soft glow behind the headline and a faint starfield."""
    img = Image.new("RGB", (W, H), (14, 11, 38))
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    for cx, cy, r, c in [(560, 380, 620, (46, 32, 110)), (1450, 520, 700, (30, 26, 92)), (960, 1120, 900, (6, 6, 16))]:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=c)
    glow = glow.filter(ImageFilter.GaussianBlur(220))
    img = ImageChops.add(img, glow)
    stars = ImageDraw.Draw(img)
    rnd = random.Random(7)
    for _ in range(260):
        x, y = rnd.randrange(W), rnd.randrange(H)
        b = rnd.randrange(70, 190)
        s = rnd.choice([1, 1, 1, 2])
        stars.ellipse((x, y, x + s, y + s), fill=(b, b, min(255, b + 30)))
    return img.convert("RGBA")

def gradient_text(canvas, xy, text, fnt, left=(198, 180, 255), right=(240, 236, 255)):
    """Lavender-to-white headline, like the Dependency Hub banner."""
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).multiline_text(xy, text, font=fnt, fill=255, spacing=6)
    bbox = mask.getbbox()
    grad = Image.new("RGBA", canvas.size)
    gd = ImageDraw.Draw(grad)
    x0, x1 = bbox[0], bbox[2]
    for x in range(x0, x1 + 1):
        t = (x - x0) / max(1, x1 - x0)
        gd.line((x, 0, x, H), fill=tuple(int(left[i] + (right[i] - left[i]) * t) for i in range(3)) + (255,))
    canvas.paste(grad, (0, 0), mask)

def rounded(img, r):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), r, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img.convert("RGBA"), (0, 0), m)
    return out

def card(canvas, img, xy, r=18, shadow=36, glow=None):
    c = rounded(img, r)
    pad = shadow * 2
    sh = Image.new("RGBA", (c.size[0] + pad, c.size[1] + pad), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((shadow, shadow, shadow + c.size[0], shadow + c.size[1]), r,
                                         fill=glow or (0, 0, 0, 170))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(shadow / 2)), (xy[0] - shadow, xy[1] - shadow + 12))
    canvas.alpha_composite(c, xy)

def fit(img, mw, mh):
    s = min(mw / img.size[0], mh / img.size[1])
    return img.resize((int(img.size[0] * s), int(img.size[1] * s)), Image.LANCZOS)

def window(panel, title):
    bar = 40
    w, h = panel.size
    f = Image.new("RGB", (w, h + bar), (43, 45, 48))
    d = ImageDraw.Draw(f)
    for i, c in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        d.ellipse((16 + i * 22, 14, 28 + i * 22, 26), fill=c)
    d.text((w // 2, bar // 2), title, fill=(200, 202, 210), font=font(18, True), anchor="mm")
    f.paste(panel.convert("RGB"), (0, bar))
    return f

canvas = background()

# Left: icon, headline, subtitle, features.
icon = Image.open(RENDERS / "plugin-icon.png").convert("RGBA").resize((108, 108), Image.LANCZOS)
canvas.alpha_composite(icon, (96, 112))
gradient_text(canvas, (92, 262), "Every device,\none panel", font(104, True))
d = ImageDraw.Draw(canvas)
d.multiline_text((96, 520), "Run, configure and inspect your Kotlin\nMultiplatform app on Android and iOS,\nwithout leaving Android Studio.",
                 font=font(34), fill=(205, 200, 235), spacing=12)
features = [
    "Emulators, phones and iOS simulators together",
    "Dark mode, text size, RTL and permissions",
    "Network Inspector for Ktor and OkHttp",
    "Location, slow network, push and 9:41 status bar",
]
for i, f in enumerate(features):
    y = 700 + i * 54
    # Drawn checkmark: Helvetica has no ✓ glyph.
    d.line([(100, y + 19), (110, y + 29), (128, y + 8)], fill=(74, 222, 128), width=5, joint="curve")
    d.text((146, y + 2), f, font=font(30), fill=(232, 230, 245))

# Right: the real Devices panel and two real device screens in a row, the Network window underneath.
devices = fit(window(Image.open(RENDERS / "devices-settings.png"), "KMP Devices"), 400, 620)
android = fit(Image.open(FOOTAGE / "android-rtl.png"), 300, 590)
iphone = fit(Image.open(FOOTAGE / "ios-dark.png"), 300, 590)
network = fit(window(Image.open(RENDERS / "network-json.png"), "KMP Network"), 860, 290)

card(canvas, devices, (1000, 112), r=14, glow=(127, 82, 255, 110))
card(canvas, android, (1438, 128), r=40, glow=(30, 136, 229, 120))
card(canvas, iphone, (1618, 160), r=40, glow=(127, 82, 255, 130))
card(canvas, network, (1000, 776), r=14, glow=(30, 136, 229, 90))

OUT.parent.mkdir(parents=True, exist_ok=True)
canvas.convert("RGB").save(OUT, optimize=True)
print("wrote", OUT)

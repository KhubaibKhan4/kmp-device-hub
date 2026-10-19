"""Three YouTube thumbnails (1280x720) for A/B testing, each with a different hook.

python3 tools/listing/make_thumbnails.py idea-plugin/build/listing <device-screenshots-dir> dist/listing/thumbnails
"""
import random
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

RENDERS, FOOTAGE, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
OUT.mkdir(parents=True, exist_ok=True)
W, H = 1280, 720
HN = "/System/Library/Fonts/HelveticaNeue.ttc"
BLACK, BOLD, MEDIUM = 9, 1, 10      # Condensed Black, Bold, Medium
YELLOW, GREEN, WHITE = (255, 214, 10), (74, 222, 128), (255, 255, 255)

def font(size, index=BLACK):
    return ImageFont.truetype(HN, size, index=index)

def background(c1=(46, 32, 110), c2=(14, 11, 38), glow_xy=(380, 260)):
    img = Image.new("RGB", (W, H), c2)
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    gx, gy = glow_xy
    d.ellipse((gx - 520, gy - 520, gx + 520, gy + 520), fill=c1)
    img = ImageChops.add(img, glow.filter(ImageFilter.GaussianBlur(170)))
    s = ImageDraw.Draw(img)
    rnd = random.Random(3)
    for _ in range(120):
        x, y = rnd.randrange(W), rnd.randrange(H)
        b = rnd.randrange(60, 170)
        s.point((x, y), fill=(b, b, b + 20 if b < 235 else 255))
    return img.convert("RGBA")

def rounded(img, r):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), r, fill=255)
    o = Image.new("RGBA", img.size)
    o.paste(img.convert("RGBA"), (0, 0), m)
    return o

def card(canvas, img, xy, r=26, shadow=26, glow=(0, 0, 0, 190), border=None, rotate=0):
    c = rounded(img, r)
    if border:
        b = Image.new("RGBA", (c.size[0] + 10, c.size[1] + 10), (0, 0, 0, 0))
        ImageDraw.Draw(b).rounded_rectangle((0, 0, b.size[0] - 1, b.size[1] - 1), r + 5, fill=border)
        b.alpha_composite(c, (5, 5))
        c = b
    if rotate:
        c = c.rotate(rotate, resample=Image.BICUBIC, expand=True)
    sh = Image.new("RGBA", (c.size[0] + shadow * 2, c.size[1] + shadow * 2), (0, 0, 0, 0))
    sh.paste(Image.new("RGBA", c.size, glow), (shadow, shadow), c.split()[3])
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(shadow / 2)), (xy[0] - shadow, xy[1] - shadow + 10))
    canvas.alpha_composite(c, xy)

def fit(img, mw, mh):
    s = min(mw / img.size[0], mh / img.size[1])
    return img.resize((int(img.size[0] * s), int(img.size[1] * s)), Image.LANCZOS)

def outlined(d, xy, text, fnt, fill, stroke=6, anchor="la"):
    """Heavy text with a dark outline so it reads at phone-thumbnail size."""
    d.text(xy, text, font=fnt, fill=fill, stroke_width=stroke, stroke_fill=(10, 8, 26), anchor=anchor)

def icon(canvas, xy, size):
    i = Image.open(RENDERS / "plugin-icon.png").convert("RGBA").resize((size, size), Image.LANCZOS)
    canvas.alpha_composite(i, xy)

def window(panel, title):
    bar = 30
    w, h = panel.size
    f = Image.new("RGB", (w, h + bar), (43, 45, 48))
    d = ImageDraw.Draw(f)
    for i, c in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        d.ellipse((12 + i * 18, 10, 22 + i * 18, 20), fill=c)
    d.text((w // 2, bar // 2), title, fill=(200, 202, 210), font=font(15, BOLD), anchor="mm")
    f.paste(panel.convert("RGB"), (0, bar))
    return f

# ---- A: Android + iOS, one panel --------------------------------------------------------------------------
a = background(glow_xy=(330, 330))
d = ImageDraw.Draw(a)
icon(a, (64, 58), 92)
outlined(d, (60, 178), "ANDROID", font(132), WHITE)
outlined(d, (60, 312), "+ iOS", font(132), YELLOW)
outlined(d, (62, 452), "ONE PANEL", font(96), WHITE)
d.rounded_rectangle((64, 584, 512, 648), 32, fill=(127, 82, 255))
d.text((288, 616), "Android Studio plugin", font=font(34, BOLD), fill=WHITE, anchor="mm")
panel = fit(window(Image.open(RENDERS / "devices-app.png").crop((0, 0, 880, 1040)), "KMP Devices"), 360, 520)
card(a, panel, (640, 120), r=14, glow=(127, 82, 255, 160), rotate=4)
card(a, fit(Image.open(FOOTAGE / "android-dark.png"), 250, 560), (860, 96), r=30, glow=(30, 136, 229, 170), border=(255, 255, 255, 40), rotate=-3)
card(a, fit(Image.open(FOOTAGE / "ios-dark.png"), 250, 560), (1010, 150), r=30, glow=(127, 82, 255, 170), border=(255, 255, 255, 40), rotate=5)
a.convert("RGB").save(OUT / "thumbnail-A-one-panel.png")

# ---- B: before → after, RTL + dark mode in one click ----------------------------------------------------
b = background(c1=(20, 60, 120), glow_xy=(900, 360))
d = ImageDraw.Draw(b)
before = fit(Image.open(FOOTAGE / "android-light.png"), 260, 580)
after = fit(Image.open(FOOTAGE / "android-rtl.png"), 260, 580)
card(b, before, (560, 110), r=30, glow=(0, 0, 0, 200), border=(255, 255, 255, 60))
card(b, after, (960, 80), r=30, glow=(255, 214, 10, 150), border=YELLOW)
# Arrow between the phones.
d.polygon([(850, 340), (920, 340), (920, 312), (958, 380), (920, 448), (920, 420), (850, 420)], fill=YELLOW, outline=(10, 8, 26))
outlined(d, (690, 674), "BEFORE", font(36, BOLD), WHITE, stroke=4, anchor="mm")
outlined(d, (1090, 686), "1 CLICK LATER", font(36, BOLD), YELLOW, stroke=4, anchor="mm")
icon(b, (58, 56), 84)
outlined(d, (54, 168), "RTL +", font(140), YELLOW)
outlined(d, (54, 310), "DARK", font(140), WHITE)
outlined(d, (54, 452), "MODE", font(140), WHITE)
outlined(d, (58, 610), "in 1 click", font(54, BOLD), GREEN, stroke=5)
b.convert("RGB").save(OUT / "thumbnail-B-rtl-dark.png")

# ---- C: see every API call ----------------------------------------------------------------------------------
c = background(c1=(70, 24, 60), glow_xy=(1000, 300))
d = ImageDraw.Draw(c)
net = Image.open(RENDERS / "network-error.png")             # 2800x840 render (2x): call list left, error body right
rows_src = net.crop((0, 60, 1230, 360))                       # status, method, path, host, time
scale = 680 / rows_src.size[0]
rows = window(rows_src.resize((680, int(rows_src.size[1] * scale)), Image.LANCZOS), "KMP Network")
rows_xy = (560, 150)
card(c, rows, rows_xy, r=14, glow=(255, 80, 80, 120))
# Outline the 402 row (third row; rows are 48 px apart at 2x, starting at y≈80 in the render).
row_top = rows_xy[1] + 30 + int((80 + 2 * 48 - 60 - 14) * scale) - 14
d.rounded_rectangle((rows_xy[0] - 8, row_top, rows_xy[0] + 688, row_top + int(48 * scale) + 6), 10, outline=(229, 57, 53), width=5)
body_src = net.crop((1700, 120, 2800, 380))                    # the 402 response: headers and JSON error
body = fit(body_src, 520, 240)
card(c, body, (720, 410), r=12, glow=(229, 57, 53, 170), border=(229, 57, 53, 255))
d.rounded_rectangle((1040, 362, 1240, 422), 16, fill=(229, 57, 53))
d.text((1140, 392), "402 ERROR", font=font(40), fill=WHITE, anchor="mm")
icon(c, (58, 56), 84)
outlined(d, (54, 170), "SEE EVERY", font(112), WHITE)
outlined(d, (54, 292), "API CALL", font(112), YELLOW)
outlined(d, (58, 440), "Ktor · OkHttp", font(56, BOLD), WHITE, stroke=5)
outlined(d, (58, 512), "Android + iOS", font(56, BOLD), GREEN, stroke=5)
c.convert("RGB").save(OUT / "thumbnail-C-network.png")

print("wrote", sorted(p.name for p in OUT.glob("thumbnail-*.png")))

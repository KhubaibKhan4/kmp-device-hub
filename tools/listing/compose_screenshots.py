"""Composes Marketplace screenshots (2560x1600) from plugin panel renders and real device screenshots."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RENDERS = Path(sys.argv[1])   # idea-plugin/build/listing
FOOTAGE = Path(sys.argv[2])   # device screenshots
ICON = Path(sys.argv[3])      # plugin icon png
OUT = Path(sys.argv[4]); OUT.mkdir(parents=True, exist_ok=True)
ONLY = set(sys.argv[5:])      # optional: just these screenshots, e.g. 10-crashes.png

W, H = 2560, 1600
FONT = "/System/Library/Fonts/SFNS.ttf"
def font(size, bold=False):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name("Bold" if bold else "Regular")
    except Exception:
        pass
    return f

def background():
    """Diagonal gradient in the plugin icon's colors, darkened for contrast."""
    top, mid, bottom = (40, 22, 92), (26, 30, 88), (10, 44, 86)
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        for x in range(0, W, 4):
            t = (x / W * 0.45 + y / H * 0.55)
            a, b, u = (top, mid, t / 0.55) if t < 0.55 else (mid, bottom, (t - 0.55) / 0.45)
            c = tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))
            for dx in range(4):
                if x + dx < W: px[x + dx, y] = c
    return img

BG = background()

def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img.convert("RGBA"), (0, 0), mask)
    return out

def paste_card(canvas, img, box_xy, radius=28, shadow=40):
    """Pastes with rounded corners and a soft shadow."""
    card = rounded(img, radius)
    sh = Image.new("RGBA", (card.size[0] + shadow * 2, card.size[1] + shadow * 2), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((shadow, shadow, shadow + card.size[0], shadow + card.size[1]), radius, fill=(0, 0, 0, 150))
    sh = sh.filter(ImageFilter.GaussianBlur(shadow / 2))
    canvas.alpha_composite(sh, (box_xy[0] - shadow, box_xy[1] - shadow + 14))
    canvas.alpha_composite(card, box_xy)

def fit(img, max_w, max_h):
    s = min(max_w / img.size[0], max_h / img.size[1])
    return img.resize((int(img.size[0] * s), int(img.size[1] * s)), Image.LANCZOS)

def window(panel, title):
    """Wraps a panel render in a minimal IDE-like tool window frame."""
    bar = 64
    w, h = panel.size
    frame = Image.new("RGB", (w, h + bar), (247, 248, 250))
    d = ImageDraw.Draw(frame)
    d.rectangle((0, 0, w, bar), fill=(235, 236, 240))
    for i, c in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        d.ellipse((24 + i * 34, 22, 44 + i * 34, 42), fill=c)
    d.text((w // 2, bar // 2), title, fill=(60, 63, 70), font=font(28, True), anchor="mm")
    frame.paste(panel.convert("RGB"), (0, bar))
    return frame

def headline(canvas, title, subtitle, x=140, y=120, width=None):
    d = ImageDraw.Draw(canvas)
    d.text((x, y), title, fill=(255, 255, 255), font=font(88, True))
    d.text((x, y + 120), subtitle, fill=(200, 205, 235), font=font(44))

def badge(canvas):
    icon = Image.open(ICON).convert("RGBA").resize((88, 88), Image.LANCZOS)
    canvas.alpha_composite(rounded(icon, 20), (W - 140 - 88, 116))
    ImageDraw.Draw(canvas).text((W - 140 - 88 - 24, 160), "KMP Device Hub", fill=(225, 228, 245), font=font(40, True), anchor="rm")

def slide(name, title, subtitle, panel, panel_title, phones, panel_box=(140, 380, 1150, 1150)):
    if ONLY and name not in ONLY: return
    canvas = BG.copy().convert("RGBA")
    headline(canvas, title, subtitle)
    badge(canvas)
    win = fit(window(Image.open(RENDERS / panel), panel_title), panel_box[2], panel_box[3])
    paste_card(canvas, win, (panel_box[0], panel_box[1]))
    if phones:
        area_x = panel_box[0] + win.size[0] + 110
        area_w = W - area_x - 140
        n = len(phones)
        gap = 60
        ph_w = (area_w - gap * (n - 1)) // n
        for i, p in enumerate(phones):
            img = fit(Image.open(FOOTAGE / p), ph_w, 1120)
            x = area_x + i * (ph_w + gap) + (ph_w - img.size[0]) // 2
            paste_card(canvas, img, (x, 390), radius=56)
    canvas.convert("RGB").save(OUT / name, optimize=True)
    print("wrote", name)

def wide(name, title, subtitle, panel, panel_title):
    if ONLY and name not in ONLY: return
    canvas = BG.copy().convert("RGBA")
    headline(canvas, title, subtitle)
    badge(canvas)
    win = fit(window(Image.open(RENDERS / panel), panel_title), W - 280, 1100)
    paste_card(canvas, win, ((W - win.size[0]) // 2, 400 + (1100 - win.size[1]) // 2))
    canvas.convert("RGB").save(OUT / name, optimize=True)
    print("wrote", name)

slide("01-devices.png", "Every device, one panel",
      "Android emulators, phones and iOS simulators: start, stop and run your app on any of them.",
      "devices-app.png", "KMP Devices", ["android-dark.png", "ios-dark.png"])
slide("02-appearance-language.png", "Dark mode, text size and RTL",
      "Flip appearance, dynamic type and your app's language on Android and iOS, then see it relaunch.",
      "devices-settings.png", "KMP Devices", ["ios-largest.png", "android-rtl.png"])
slide("03-permissions.png", "Permissions without digging in Settings",
      "See what your app was granted, then grant, revoke or reset with one click.",
      "devices-permissions.png", "KMP Devices", ["ios-rtl.png"])
wide("04-network-inspector.png", "Network Inspector for KMP apps",
     "Every Ktor and OkHttp call: status, timing, headers, bodies and a JSON tree. Add one line to your HttpClient.",
     "network-json.png", "KMP Network")
wide("05-network-errors.png", "Catch errors as they happen",
     "4xx/5xx and failed calls stand out. Copy as cURL, or mock and fail requests to test error states.",
     "network-error.png", "KMP Network")
slide("06-simulate.png", "Simulate the real world",
      "Location, slow or offline networks, push notifications and a clean 9:41 status bar for store shots.",
      "devices-simulate.png", "KMP Devices", ["ios-push.png"])
slide("07-presets.png", "Test presets in one click",
      "Dark + largest text, right-to-left, slow network, store screenshots, or save your own.",
      "devices-presets.png", "KMP Devices", ["android-rtl.png", "ios-rtl.png"])
wide("08-logs.png", "Logs from Android and iOS",
     "Your app's logcat and iOS simulator log in one window: filtered to your app, color-coded, searchable. println too.",
     "logs.png", "KMP Logs")
wide("09-app-data.png", "Look inside your app's data",
     "Browse files, SharedPreferences and NSUserDefaults, and query Room / SQLDelight / Core Data databases with SQL.",
     "app-data.png", "KMP App Data")
wide("10-crashes.png", "Crashes, straight to the line",
     "Android, Kotlin/Native and Swift crashes are listed with their stack traces. Click through to your code.",
     "logs-crash.png", "KMP Logs")

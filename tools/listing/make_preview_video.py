"""Builds the KMP Device Hub preview video (1920x1080, ~65 s) from listing shots, panel renders and device recordings."""
import subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RENDERS, FOOTAGE, LISTING, OUT = map(Path, sys.argv[1:5])
WORK = OUT.parent / "video-work"; WORK.mkdir(parents=True, exist_ok=True)
W, H, FPS = 1920, 1080, 30
FONT = "/System/Library/Fonts/SFNS.ttf"

def font(size, bold=False):
    f = ImageFont.truetype(FONT, size)
    try: f.set_variation_by_name("Bold" if bold else "Regular")
    except Exception: pass
    return f

def background():
    top, mid, bottom = (40, 22, 92), (26, 30, 88), (10, 44, 86)
    img = Image.new("RGB", (W, H)); px = img.load()
    for y in range(H):
        for x in range(0, W, 4):
            t = x / W * 0.45 + y / H * 0.55
            a, b, u = (top, mid, t / 0.55) if t < 0.55 else (mid, bottom, (t - 0.55) / 0.45)
            c = tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3))
            for dx in range(4):
                if x + dx < W: px[x + dx, y] = c
    return img
BG = background()

def rounded(img, r):
    m = Image.new("L", img.size, 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), r, fill=255)
    o = Image.new("RGBA", img.size); o.paste(img.convert("RGBA"), (0, 0), m); return o

def card(canvas, img, xy, r=22, sh=30):
    c = rounded(img, r)
    s = Image.new("RGBA", (c.size[0] + sh * 2, c.size[1] + sh * 2), (0, 0, 0, 0))
    ImageDraw.Draw(s).rounded_rectangle((sh, sh, sh + c.size[0], sh + c.size[1]), r, fill=(0, 0, 0, 150))
    canvas.alpha_composite(s.filter(ImageFilter.GaussianBlur(sh / 2)), (xy[0] - sh, xy[1] - sh + 10))
    canvas.alpha_composite(c, xy)

def fit(img, mw, mh):
    s = min(mw / img.size[0], mh / img.size[1]); return img.resize((int(img.size[0] * s), int(img.size[1] * s)), Image.LANCZOS)

def window(panel, title):
    bar = 48; w, h = panel.size
    f = Image.new("RGB", (w, h + bar), (247, 248, 250)); d = ImageDraw.Draw(f)
    d.rectangle((0, 0, w, bar), fill=(235, 236, 240))
    for i, c in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]): d.ellipse((18 + i * 26, 16, 34 + i * 26, 32), fill=c)
    d.text((w // 2, bar // 2), title, fill=(60, 63, 70), font=font(22, True), anchor="mm")
    f.paste(panel.convert("RGB"), (0, bar)); return f

def run(*args):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *args], check=True)

def still(name, img, seconds):
    png = WORK / f"{name}.png"; img.convert("RGB").save(png)
    out = WORK / f"{name}.mp4"
    run("-loop", "1", "-framerate", str(FPS), "-i", str(png), "-t", str(seconds), "-vf",
        f"fps={FPS},format=yuv420p,fade=t=in:st=0:d=0.4,fade=t=out:st={seconds - 0.4}:d=0.4",
        "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(out))
    return out

def title_card(name, big, small, seconds, icon_size=180):
    c = BG.copy().convert("RGBA")
    icon = Image.open(RENDERS / "plugin-icon.png").convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
    c.alpha_composite(icon, ((W - icon_size) // 2, 260))
    d = ImageDraw.Draw(c)
    d.text((W // 2, 540), big, fill="white", font=font(96, True), anchor="mm")
    for i, line in enumerate(small.split("\n")):
        d.text((W // 2, 650 + i * 64), line, fill=(200, 205, 235), font=font(44), anchor="mm")
    return still(name, c, seconds)

def listing_shot(name, file, seconds):
    """A 2560x1600 listing screenshot fitted into 16:9 on the same background."""
    c = BG.copy().convert("RGBA")
    shot = Image.open(LISTING / file).convert("RGBA").resize((1728, 1080), Image.LANCZOS)
    c.alpha_composite(shot, ((W - 1728) // 2, 0))
    return still(name, c, seconds)

def clip_scene(name, clip, panel, caption, sub, speed, trim_start=0.0, length=None):
    """Caption and plugin panel on the left, the real device recording on the right."""
    c = BG.copy().convert("RGBA"); d = ImageDraw.Draw(c)
    d.text((90, 80), caption, fill="white", font=font(64, True))
    for i, line in enumerate(sub.split("\n")):
        d.text((90, 168 + i * 46), line, fill=(200, 205, 235), font=font(34))
    win = fit(window(Image.open(RENDERS / panel), "KMP Devices"), 560, 760)
    card(c, win, (90, 290))
    bg = WORK / f"{name}-bg.png"; c.convert("RGB").save(bg)
    clip_h = 860
    # Scaled clip size (keeps the device aspect ratio), plus a rounded-corner mask for it.
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                            "-of", "csv=p=0", str(clip)], capture_output=True, text=True).stdout.strip().split(",")
    cw = int(int(probe[0]) * clip_h / int(probe[1])) // 2 * 2
    mask = Image.new("L", (cw, clip_h), 0); ImageDraw.Draw(mask).rounded_rectangle((0, 0, cw - 1, clip_h - 1), 44, fill=255)
    mpath = WORK / f"{name}-mask.png"; mask.save(mpath)
    x = 760 + (W - 760 - cw) // 2; y = 170
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(clip)],
                               capture_output=True, text=True).stdout.strip())
    seconds = ((length if length else dur - trim_start)) / speed
    out = WORK / f"{name}.mp4"
    run("-loop", "1", "-framerate", str(FPS), "-i", str(bg), "-ss", str(trim_start), "-t", f"{length if length else dur - trim_start:.2f}", "-i", str(clip), "-loop", "1", "-i", str(mpath),
        "-filter_complex",
        f"[1:v]setpts=(PTS-STARTPTS)/{speed},fps={FPS},tpad=stop_mode=clone:stop_duration=30,scale={cw}:{clip_h},format=rgba[v];"
        f"[2:v]format=gray[m];[v][m]alphamerge[vm];"
        f"[0:v][vm]overlay={x}:{y},format=yuv420p,fade=t=in:st=0:d=0.4,fade=t=out:st={seconds - 0.5}:d=0.4[o]",
        "-map", "[o]", "-t", f"{seconds:.2f}", "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(out))
    return out

scenes = [
    title_card("00-title", "KMP Device Hub", "Every Android device, emulator and iOS simulator in one panel\nfor Kotlin Multiplatform developers", 4.0),
    listing_shot("01-devices", "01-devices.png", 4.0),
    clip_scene("02-ios", FOOTAGE / "ios.mov", "devices-settings.png", "iOS simulator, from the IDE",
               "Dark mode · largest text · Arabic RTL · push\nReal iPhone 17 simulator at 1.5× speed", 1.5, trim_start=0.0, length=30.0),
    clip_scene("03-android", FOOTAGE / "android.mp4", "devices-settings.png", "Same controls on Android",
               "Dark mode · Arabic RTL with app relaunch\nReal emulator at 1.5× speed", 1.5, trim_start=0.5),
    listing_shot("04-network", "04-network-inspector.png", 4.5),
    listing_shot("05-errors", "05-network-errors.png", 3.5),
    listing_shot("06-simulate", "06-simulate.png", 3.5),
    listing_shot("07-presets", "07-presets.png", 3.5),
    title_card("08-end", "Free on JetBrains Marketplace", "Search “KMP Device Hub” in Settings → Plugins\nNetwork library: io.github.khubaibkhan4:devicehub-network", 4.5),
]
lst = WORK / "concat.txt"; lst.write_text("".join(f"file '{s}'\n" for s in scenes))
run("-f", "concat", "-safe", "0", "-i", str(lst), "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-pix_fmt", "yuv420p",
    "-movflags", "+faststart", str(OUT))
print("wrote", OUT)

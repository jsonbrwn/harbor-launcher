# Generates the launcher icon and TV banner (original artwork).
import math, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img

def art(w, h):
    s = 4
    W, H = w * s, h * s
    img = gradient(W, H, (40, 36, 110), (236, 120, 96)).convert("RGBA")
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    r = min(W, H) * 0.22
    cx, cy = W * 0.5, H * 0.46
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 214, 150, 255))
    glow = glow.filter(ImageFilter.GaussianBlur(min(W, H) * 0.02))
    img.alpha_composite(glow)
    # two soft wave bands
    for k, (col, base, amp) in enumerate([((90, 60, 160, 235), 0.62, 0.05), ((30, 24, 70, 255), 0.74, 0.04)]):
        band = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        bd = ImageDraw.Draw(band)
        pts = []
        for i in range(0, 101):
            x = W * i / 100
            y = H * base + H * amp * math.sin(i / 100 * math.pi * 2 + k * 1.3)
            pts.append((x, y))
        pts += [(W, H), (0, H)]
        bd.polygon(pts, fill=col)
        img.alpha_composite(band)
    return img.resize((w, h), Image.LANCZOS)

def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out

out = sys.argv[1]
icon = rounded(art(96, 96), 22)
icon.save(out + "/res/mipmap-xhdpi/ic_launcher.png")

banner = art(320, 180)
d = ImageDraw.Draw(banner)
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 40)
except Exception:
    font = ImageFont.load_default()
t = "Harbor"
bb = d.textbbox((0, 0), t, font=font)
d.text(((320 - (bb[2] - bb[0])) / 2, 118), t, font=font, fill=(255, 255, 255, 240))
banner.convert("RGB").save(out + "/res/drawable-xhdpi/banner.png")
print("icons written")

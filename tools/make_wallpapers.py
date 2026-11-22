"""Generates Harbor's 25 original illustrated road-racing-circuit wallpapers (1920x1080 JPEG).

Every scene is drawn from scratch by this script, so there are no licensing strings attached.
Composition rule: the top two-thirds stay calm (sky, distant scenery) where the clock sits, and
the circuit sweeps through the lower third behind the dock.

Usage: python3 make_wallpapers.py OUT_DIR
"""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
S = 2
SW, SH = W * S, H * S


def lerp(a, b, t): return a + (b - a) * t
def mix(c1, c2, t): return tuple(int(lerp(c1[i], c2[i], t)) for i in range(3))


def vgrad(size, stops):
    w, h = size
    col = Image.new("RGB", (1, h))
    px = col.load()
    for y in range(h):
        t = y / (h - 1)
        for i in range(len(stops) - 1):
            t0, c0 = stops[i]; t1, c1 = stops[i + 1]
            if t0 <= t <= t1:
                px[0, y] = mix(c0, c1, (t - t0) / max(1e-6, t1 - t0)); break
        else:
            px[0, y] = stops[-1][1]
    return col.resize((w, h))


def ridge(base, amp, seed, octaves=4, rough=1.0):
    rnd = random.Random(seed)
    ph = [rnd.uniform(0, 6.28) for _ in range(octaves)]
    pts = []
    for i in range(241):
        y = sum(math.sin(i / 240 * math.pi * 2 * ((o + 1) ** 1.6) * 0.8 * rough + ph[o]) / (o + 1) ** 1.2 for o in range(octaves))
        pts.append((SW * i / 240, SH * base - SH * amp * y))
    return pts


def new_layer(img, color=(0, 0, 0)):
    return Image.new("RGBA", img.size, tuple(color[:3]) + (0,))


def poly(img, pts, fill, blur=0):
    lay = new_layer(img, fill)
    ImageDraw.Draw(lay).polygon(pts + [(SW, SH), (0, SH)], fill=fill)
    if blur: lay = lay.filter(ImageFilter.GaussianBlur(blur * S))
    img.alpha_composite(lay)


def glow(img, cx, cy, r, color, alpha=255, blur=60):
    lay = new_layer(img, color)
    ImageDraw.Draw(lay).ellipse([cx - r, cy - r, cx + r, cy + r], fill=tuple(color) + (alpha,))
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(blur * S)))


def spline(p, per=80):
    out = []
    pts = [p[0]] + p + [p[-1]]
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for k in range(per):
            t = k / per; t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * ((2 * p1[j]) + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t2 + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t3) for j in range(2)))
    out.append(p[-1])
    return out


# Track layouts in screen fractions, drawn far (horizon) to near (bottom).
LAYOUTS = [
    [(-0.05, 0.700), (0.30, 0.695), (0.55, 0.715), (0.62, 0.765), (0.45, 0.83), (0.50, 0.93), (0.70, 1.06)],
    [(1.05, 0.705), (0.70, 0.700), (0.40, 0.720), (0.30, 0.770), (0.55, 0.84), (0.85, 0.92), (1.08, 0.97)],
    [(0.45, 0.665), (0.52, 0.690), (0.40, 0.730), (0.30, 0.790), (0.48, 0.87), (0.62, 0.96), (0.66, 1.06)],
    [(-0.05, 0.86), (0.18, 0.80), (0.33, 0.735), (0.52, 0.705), (0.70, 0.72), (0.85, 0.75), (1.05, 0.745)],
    [(-0.05, 0.735), (0.25, 0.725), (0.50, 0.745), (0.80, 0.80), (0.95, 0.88), (0.80, 0.97), (0.55, 1.06)],
    [(0.60, 0.670), (0.66, 0.700), (0.58, 0.740), (0.70, 0.800), (0.55, 0.88), (0.30, 0.95), (0.12, 1.06)],
    [(1.05, 0.83), (0.80, 0.79), (0.62, 0.74), (0.42, 0.715), (0.22, 0.73), (0.08, 0.77), (-0.05, 0.78)],
]


def track(img, layout, horizon, asphalt, edge=(238, 238, 238), kerb=((206, 42, 42), (242, 242, 242)), wmin=8, wmax=250, mirror=False):
    ctrl = [((1 - x) if mirror else x, y) for x, y in layout]
    c = spline([(x * SW, y * SH) for x, y in ctrl])

    def width(y):
        t = max(0.0, min(1.0, (y - horizon * SH) / (SH - horizon * SH)))
        return lerp(wmin, wmax, t ** 1.3) * S

    def turn(i):
        a = c[max(0, i - 8)]; b = c[i]; e = c[min(len(c) - 1, i + 8)]
        v1 = (b[0] - a[0], b[1] - a[1]); v2 = (e[0] - b[0], e[1] - b[1])
        n = (math.hypot(*v1) * math.hypot(*v2)) or 1
        return abs(v1[0] * v2[1] - v1[1] * v2[0]) / n

    def stroke(d, scale, colorfn):
        for i in range(len(c) - 1):
            col = colorfn(i)
            if col is None: continue
            wv = width(c[i][1]) * scale
            d.line([c[i], c[i + 1]], fill=col, width=max(1, int(wv)))
            r = wv / 2
            d.ellipse([c[i][0] - r, c[i][1] - r, c[i][0] + r, c[i][1] + r], fill=col)

    shadow = new_layer(img)
    stroke(ImageDraw.Draw(shadow), 1.25, lambda i: (0, 0, 0, 70))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(9 * S)))

    lay = new_layer(img, asphalt)
    d = ImageDraw.Draw(lay)
    seg = [0]
    def kerbcol(i):
        if turn(i) < 0.05: return None
        return kerb[(i // 6) % 2] + (255,)
    stroke(d, 1.16, kerbcol)
    stroke(d, 1.07, lambda i: edge + (240,))
    stroke(d, 1.0, lambda i: asphalt + (255,))
    img.alpha_composite(lay)
    # subtle sheen down the racing line
    sheen = new_layer(img, (255, 255, 255))
    stroke(ImageDraw.Draw(sheen), 0.35, lambda i: (255, 255, 255, 14))
    img.alpha_composite(sheen.filter(ImageFilter.GaussianBlur(6 * S)))


def trees(img, rnd, y0, y1, n, color, xr=(0, 1), size=(16, 40), kind="pine", clear=None):
    lay = new_layer(img, color)
    d = ImageDraw.Draw(lay)
    items = sorted((rnd.uniform(y0, y1), rnd.uniform(*xr)) for _ in range(n))
    for y, x in items:
        t = (y - y0) / max(1e-6, y1 - y0)
        s = lerp(size[0], size[1], t) * S
        px, py = x * SW, y * SH
        if clear and any(abs(px - cx * SW) < s * 1.6 + cw * SW and abs(py - cy * SH) < s * 1.2 for cx, cy, cw in clear):
            continue
        if kind == "pine":
            d.polygon([(px, py - s * 2.2), (px - s * 0.55, py), (px + s * 0.55, py)], fill=color)
        elif kind == "round":
            d.ellipse([px - s * 0.7, py - s * 1.6, px + s * 0.7, py - s * 0.2], fill=color)
            d.rectangle([px - s * 0.08, py - s * 0.4, px + s * 0.08, py], fill=color)
        elif kind == "cypress":
            d.ellipse([px - s * 0.28, py - s * 2.4, px + s * 0.28, py], fill=color)
    img.alpha_composite(lay)


def clear_zones(layout, mirror):
    return [(((1 - x) if mirror else x), y, 0.03) for x, y in spline([(x, y) for x, y in layout], per=10)]


# ---------------------------------------------------------------- scenery pieces

def sky(img, top, mid, low, horizon):
    img.paste(vgrad((SW, SH), [(0, top), (horizon * 0.75, mid), (horizon, low), (1, low)]))


def sun(img, x, y, color, halo, size=0.06):
    glow(img, SW * x, SH * y, SH * size * 4, halo, 110, 140)
    glow(img, SW * x, SH * y, SH * size, color, 255, 26)


def stars_moon(img, rnd, mx=0.8, my=0.2):
    lay = new_layer(img, (255, 255, 255))
    d = ImageDraw.Draw(lay)
    for _ in range(280):
        x, y = rnd.uniform(0, SW), rnd.uniform(0, SH * 0.52)
        r = rnd.uniform(0.6, 1.8) * S
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, rnd.randint(80, 220)))
    img.alpha_composite(lay)
    glow(img, SW * mx, SH * my, SH * 0.16, (150, 165, 220), 70, 80)
    glow(img, SW * mx, SH * my, SH * 0.042, (242, 242, 232), 255, 5)


def clouds(img, rnd, n=6, alpha=110, tint=(255, 255, 255), y=(0.12, 0.4)):
    lay = new_layer(img, tint)
    d = ImageDraw.Draw(lay)
    for _ in range(n):
        cx, cy = rnd.uniform(0, SW), rnd.uniform(SH * y[0], SH * y[1])
        for _ in range(6):
            r = rnd.uniform(40, 100) * S
            ox, oy = rnd.uniform(-140, 140) * S, rnd.uniform(-18, 18) * S
            d.ellipse([cx + ox - r, cy + oy - r * 0.45, cx + ox + r, cy + oy + r * 0.45], fill=tint + (alpha,))
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(22 * S)))


def peaks(img, seed, base, rock, snow=(246, 248, 255), amp=0.12):
    pts = ridge(base, amp, seed, octaves=5, rough=1.3)
    poly(img, pts, rock, 2)
    lay = new_layer(img, snow)
    d = ImageDraw.Draw(lay)
    d.polygon(pts + [(x, y + SH * 0.05) for x, y in pts][::-1], fill=snow + (255,))
    # keep snow only near the tops
    mask = Image.new("L", img.size, 0)
    md = ImageDraw.Draw(mask)
    top = min(y for _, y in pts)
    md.rectangle([0, 0, SW, top + (SH * base - top) * 0.8], fill=255)
    lay.putalpha(Image.composite(lay.getchannel("A"), Image.new("L", img.size, 0), mask))
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(2.5 * S)))


def water(img, y, color, shine=None, sx=0.7):
    lay = new_layer(img, color)
    ImageDraw.Draw(lay).rectangle([0, SH * y, SW, SH], fill=color + (255,))
    img.alpha_composite(lay)
    if shine:
        s = new_layer(img, shine)
        ImageDraw.Draw(s).rectangle([SW * (sx - 0.12), SH * y, SW * (sx + 0.12), SH * (y + 0.07)], fill=shine + (100,))
        img.alpha_composite(s.filter(ImageFilter.GaussianBlur(28 * S)))


def mesas(img, color, y=0.635):
    pts = [(0, SH * y), (SW * 0.52, SH * y), (SW * 0.55, SH * (y - 0.08)), (SW * 0.68, SH * (y - 0.08)), (SW * 0.71, SH * y),
           (SW * 0.80, SH * y), (SW * 0.82, SH * (y - 0.05)), (SW * 0.88, SH * (y - 0.05)), (SW * 0.90, SH * y), (SW, SH * y)]
    poly(img, pts, color, 1.5)


def city(img, rnd, y=0.645, color=(22, 22, 44), lit=(255, 210, 140)):
    lay = new_layer(img, color)
    d = ImageDraw.Draw(lay)
    x = 0
    while x < SW:
        bw = rnd.uniform(30, 90) * S; bh = rnd.uniform(0.02, 0.09) * SH
        d.rectangle([x, SH * y - bh, x + bw, SH * (y + 0.02)], fill=color + (255,))
        for _ in range(int(bw * bh / (900 * S * S))):
            wx, wy = rnd.uniform(x + 4, x + bw - 4), rnd.uniform(SH * y - bh + 4, SH * (y + 0.01))
            d.rectangle([wx, wy, wx + 2 * S, wy + 2 * S], fill=lit + (200,))
        x += bw + rnd.uniform(0, 20) * S
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(1.5 * S)))


def fog(img, y0, y1, color, alpha=150):
    lay = new_layer(img, color)
    ImageDraw.Draw(lay).rectangle([0, SH * y0, SW, SH * y1], fill=color + (alpha,))
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(30 * S)))


def finish(img, name, out):
    img = img.convert("RGB").resize((W, H), Image.LANCZOS).filter(ImageFilter.GaussianBlur(1.1))
    black = Image.new("RGB", (W, H))
    col = Image.new("L", (1, H))
    p = col.load()
    for y in range(H):
        t = y / H
        a = 0
        if t < 0.08: a = int(70 * (1 - t / 0.08))
        if t > 0.80: a = int(100 * ((t - 0.80) / 0.20) ** 1.5)
        p[0, y] = a
    img = Image.composite(black, img, col.resize((W, H)))
    img.save(f"{out}/{name}.jpg", quality=86, optimize=True, progressive=True)


# ---------------------------------------------------------------- the 25 scenes

def scene(cfg, out):
    rnd = random.Random(cfg["seed"])
    img = Image.new("RGBA", (SW, SH))
    hz = cfg.get("horizon", 0.64)
    sky(img, *cfg["sky"], hz)
    if cfg.get("stars"): stars_moon(img, rnd, *cfg.get("moon", (0.8, 0.2)))
    if cfg.get("sun"): sun(img, *cfg["sun"])
    if cfg.get("clouds"): clouds(img, rnd, *cfg["clouds"])
    feat = cfg.get("feature")
    if feat == "peaks": peaks(img, cfg["seed"], hz - 0.08, cfg["far"], amp=cfg.get("amp", 0.12))
    elif feat == "sea": water(img, hz - 0.055, cfg["water"], cfg.get("shine"), cfg.get("shine_x", 0.7))
    elif feat == "mesas": mesas(img, cfg["far"], hz - 0.005)
    elif feat == "city": city(img, rnd, hz + 0.005, cfg.get("cityc", (22, 22, 44)))
    elif feat == "hills": poly(img, ridge(hz - 0.07, 0.06, cfg["seed"] + 1), cfg["far"], 4)
    if cfg.get("fog"): fog(img, hz - 0.09, hz - 0.01, cfg["fog"])
    h = cfg["hills"]
    poly(img, ridge(hz - 0.02, 0.035, cfg["seed"] + 2), h[0], 2)
    if cfg.get("fartrees"): trees(img, rnd, hz - 0.04, hz + 0.02, 70, h[0][:3] and cfg["fartrees"], size=(6, 12), kind=cfg.get("tree", "pine"))
    poly(img, ridge(hz + 0.05, 0.035, cfg["seed"] + 3), h[1], 1)
    poly(img, ridge(hz + 0.17, 0.03, cfg["seed"] + 4), h[2])
    lay, mir = LAYOUTS[cfg["layout"]], cfg.get("mirror", False)
    tc = cfg.get("treec")
    if tc:
        trees(img, rnd, hz + 0.02, hz + 0.12, cfg.get("ntrees", 40), tc, size=(10, 22), kind=cfg.get("tree", "pine"), clear=clear_zones(lay, mir))
    track(img, lay, hz, cfg.get("asphalt", (62, 62, 68)), edge=cfg.get("edge", (238, 238, 238)), mirror=mir)
    if cfg.get("lights"):
        for x, y in spline(lay, per=3)[1::3]:
            glow(img, SW * ((1 - x) if mir else x), SH * y, SH * 0.045, (255, 220, 160), 45, 26)
    if tc and cfg.get("neartrees", True):
        trees(img, rnd, hz + 0.12, 1.02, 10, tuple(int(v * 0.7) for v in tc[:3]) + (255,), xr=(0.0, 0.14), size=(34, 70), kind=cfg.get("tree", "pine"))
        trees(img, rnd, hz + 0.12, 1.02, 10, tuple(int(v * 0.7) for v in tc[:3]) + (255,), xr=(0.86, 1.0), size=(34, 70), kind=cfg.get("tree", "pine"))
    finish(img, cfg["name"], out)


G = lambda *c: c  # tuple helper
SCENES = [
    dict(name="01-alpine-morning", seed=1, sky=((70, 120, 195), (170, 205, 235), (200, 222, 240)), feature="peaks", far=(150, 168, 200), hills=((110, 148, 128, 255), (78, 124, 86, 255), (92, 144, 82, 255)), treec=(38, 72, 52, 255), layout=0, clouds=(4, 90)),
    dict(name="02-coast-sunset", seed=2, sky=((40, 60, 120), (238, 150, 120), (255, 204, 150)), sun=(0.72, 0.53, (255, 236, 190), (255, 180, 120)), feature="sea", water=(64, 92, 140), shine=(255, 210, 160), shine_x=0.72, hills=((120, 100, 120, 255), (96, 122, 84, 255), (74, 106, 66, 255)), treec=(46, 76, 52, 255), tree="round", layout=1, ntrees=18),
    dict(name="03-desert-dusk", seed=3, sky=((40, 30, 80), (192, 92, 112), (255, 172, 102)), sun=(0.3, 0.6, (255, 230, 170), (255, 150, 100)), feature="mesas", far=(122, 62, 82, 255), hills=((176, 102, 82, 255), (192, 116, 84, 255), (206, 130, 88, 255)), layout=4, asphalt=(66, 56, 62)),
    dict(name="04-misty-forest", seed=4, sky=((112, 142, 152), (196, 210, 204), (214, 222, 214)), feature="hills", far=(150, 172, 168, 255), fog=(226, 232, 226), hills=((96, 124, 108, 255), (64, 98, 76, 255), (80, 118, 72, 255)), fartrees=(110, 140, 128, 255), treec=(42, 74, 56, 255), layout=2, ntrees=60),
    dict(name="05-night-city-circuit", seed=5, sky=((8, 12, 30), (30, 28, 66), (42, 34, 74)), stars=True, feature="city", hills=((18, 22, 38, 255), (16, 20, 34, 255), (14, 18, 30, 255)), layout=0, asphalt=(44, 46, 58), edge=(255, 240, 210), lights=True),
    dict(name="06-green-meadow", seed=6, sky=((90, 150, 222), (196, 224, 240), (210, 230, 240)), clouds=(7, 120), feature="hills", far=(130, 170, 150, 255), hills=((112, 166, 92, 255), (120, 176, 90, 255), (130, 184, 94, 255)), treec=(62, 112, 62, 255), tree="round", ntrees=24, layout=3, neartrees=False),
    dict(name="07-lakeside-dawn", seed=7, sky=((90, 110, 170), (236, 190, 190), (252, 220, 196)), sun=(0.25, 0.57, (255, 240, 210), (255, 200, 170), 0.05), feature="sea", water=(120, 140, 180), shine=(255, 220, 200), shine_x=0.25, hills=((120, 130, 150, 255), (90, 128, 100, 255), (100, 140, 92, 255)), treec=(50, 86, 64, 255), layout=5, ntrees=30),
    dict(name="08-autumn-hills", seed=8, sky=((96, 140, 200), (214, 220, 226), (232, 226, 214)), clouds=(5, 90), feature="hills", far=(160, 150, 150, 255), hills=((176, 120, 72, 255), (196, 132, 60, 255), (160, 120, 56, 255)), treec=(176, 70, 40, 255), tree="round", ntrees=40, layout=1, mirror=True),
    dict(name="09-snowy-pass", seed=9, sky=((120, 150, 200), (210, 222, 238), (232, 238, 246)), feature="peaks", far=(176, 186, 210), amp=0.15, hills=((214, 222, 236, 255), (226, 232, 242, 255), (238, 242, 248, 255)), treec=(46, 70, 66, 255), ntrees=34, layout=6, asphalt=(70, 72, 80)),
    dict(name="10-tuscan-cypress", seed=10, sky=((110, 160, 220), (236, 214, 176), (246, 226, 186)), sun=(0.82, 0.48, (255, 244, 210), (255, 214, 150), 0.04), feature="hills", far=(170, 160, 140, 255), hills=((176, 166, 104, 255), (150, 156, 82, 255), (170, 170, 92, 255)), treec=(48, 72, 44, 255), tree="cypress", ntrees=30, layout=4, mirror=True),
    dict(name="11-golden-hour-valley", seed=11, sky=((90, 110, 170), (246, 180, 110), (255, 210, 140)), sun=(0.5, 0.56, (255, 240, 200), (255, 180, 100)), feature="hills", far=(170, 120, 110, 255), hills=((150, 120, 90, 255), (120, 130, 70, 255), (110, 128, 62, 255)), treec=(64, 76, 40, 255), tree="round", ntrees=26, layout=2, mirror=True),
    dict(name="12-highland-lochs", seed=12, sky=((100, 120, 150), (176, 190, 204), (196, 206, 214)), clouds=(8, 110, (230, 234, 240)), feature="sea", water=(84, 100, 120), hills=((110, 120, 110, 255), (96, 112, 84, 255), (112, 126, 88, 255)), layout=3, mirror=True),
    dict(name="13-redrock-noon", seed=13, sky=((70, 130, 210), (176, 206, 236), (230, 214, 190)), feature="mesas", far=(170, 90, 70, 255), hills=((200, 130, 90, 255), (210, 146, 100, 255), (220, 160, 110, 255)), layout=1, asphalt=(70, 60, 64)),
    dict(name="14-twilight-peaks", seed=14, sky=((24, 30, 70), (110, 90, 150), (226, 150, 150)), feature="peaks", far=(90, 90, 130), hills=((60, 66, 100, 255), (44, 56, 80, 255), (36, 50, 66, 255)), treec=(24, 36, 48, 255), ntrees=40, layout=5, mirror=True),
    dict(name="15-tropical-bay", seed=15, sky=((60, 150, 220), (170, 220, 236), (210, 236, 236)), clouds=(5, 120), feature="sea", water=(40, 150, 170), shine=(220, 250, 250), shine_x=0.3, hills=((90, 150, 120, 255), (70, 150, 90, 255), (90, 168, 92, 255)), treec=(40, 110, 64, 255), tree="round", ntrees=24, layout=0, mirror=True),
    dict(name="16-pine-ridge-dusk", seed=16, sky=((40, 40, 90), (190, 110, 130), (246, 170, 120)), sun=(0.66, 0.6, (255, 220, 170), (240, 140, 110), 0.05), feature="hills", far=(100, 70, 100, 255), hills=((70, 60, 84, 255), (46, 52, 64, 255), (40, 54, 56, 255)), fartrees=(60, 56, 78, 255), treec=(28, 38, 44, 255), ntrees=60, layout=6, mirror=True),
    dict(name="17-moonlit-coast", seed=17, sky=((10, 16, 40), (34, 50, 90), (50, 70, 110)), stars=True, moon=(0.3, 0.22), feature="sea", water=(24, 38, 70), shine=(150, 170, 220), shine_x=0.3, hills=((26, 34, 54, 255), (22, 30, 44, 255), (20, 28, 40, 255)), layout=4, asphalt=(44, 48, 60), edge=(220, 226, 240)),
    dict(name="18-spring-orchard", seed=18, sky=((120, 170, 230), (210, 230, 244), (226, 238, 244)), clouds=(6, 110), feature="hills", far=(150, 180, 170, 255), hills=((140, 186, 110, 255), (150, 196, 108, 255), (160, 204, 112, 255)), treec=(236, 170, 196, 255), tree="round", ntrees=40, layout=5),
    dict(name="19-volcanic-dawn", seed=19, sky=((60, 50, 90), (220, 130, 120), (250, 190, 150)), sun=(0.18, 0.56, (255, 230, 190), (250, 160, 120), 0.05), feature="peaks", far=(80, 70, 90), amp=0.17, hills=((70, 66, 74, 255), (60, 74, 66, 255), (70, 90, 70, 255)), layout=3),
    dict(name="20-prairie-storm-light", seed=20, sky=((70, 80, 100), (150, 160, 170), (230, 210, 160)), clouds=(9, 140, (90, 96, 110), (0.05, 0.35)), feature="hills", far=(120, 120, 110, 255), hills=((170, 160, 100, 255), (184, 170, 96, 255), (196, 180, 100, 255)), layout=0, mirror=True),
    dict(name="21-glacier-lake", seed=21, sky=((80, 140, 210), (190, 220, 240), (214, 232, 244)), feature="peaks", far=(140, 160, 196), amp=0.14, water=(90, 160, 190), hills=((80, 130, 120, 255), (64, 118, 90, 255), (80, 136, 86, 255)), treec=(30, 70, 54, 255), ntrees=40, layout=2),
    dict(name="22-sunset-vineyard", seed=22, sky=((60, 50, 110), (230, 120, 110), (252, 186, 120)), sun=(0.42, 0.58, (255, 230, 180), (250, 150, 100)), feature="hills", far=(130, 80, 100, 255), hills=((120, 80, 90, 255), (110, 96, 70, 255), (104, 104, 64, 255)), treec=(70, 64, 46, 255), tree="cypress", ntrees=22, layout=6),
    dict(name="23-blue-hour-hills", seed=23, sky=((20, 34, 80), (60, 90, 150), (110, 140, 190)), stars=True, moon=(0.62, 0.16), feature="hills", far=(50, 70, 110, 255), hills=((40, 56, 90, 255), (32, 48, 76, 255), (28, 44, 66, 255)), treec=(20, 32, 50, 255), ntrees=40, layout=1, asphalt=(46, 50, 64), lights=True),
    dict(name="24-dune-sea", seed=24, sky=((110, 160, 220), (226, 220, 200), (244, 222, 180)), sun=(0.78, 0.4, (255, 250, 230), (255, 230, 180), 0.035), feature="hills", far=(226, 190, 140, 255), hills=((232, 186, 128, 255), (236, 196, 136, 255), (240, 206, 146, 255)), layout=5, mirror=True, asphalt=(76, 66, 66)),
    dict(name="25-northern-lights", seed=25, sky=((6, 14, 30), (14, 50, 70), (30, 70, 80)), stars=True, moon=(0.9, 0.12), feature="peaks", far=(30, 44, 60), amp=0.13, hills=((22, 34, 44, 255), (18, 30, 38, 255), (16, 26, 34, 255)), treec=(10, 20, 26, 255), ntrees=50, layout=4, asphalt=(40, 46, 56), edge=(210, 230, 230), aurora=True),
]


def aurora(img):
    lay = new_layer(img, (80, 255, 170))
    d = ImageDraw.Draw(lay)
    for k, (col, base) in enumerate([((80, 255, 170), 0.22), ((120, 200, 255), 0.30), ((160, 255, 140), 0.16)]):
        pts = [(SW * i / 60, SH * (base + 0.05 * math.sin(i / 60 * 6.0 + k))) for i in range(61)]
        for x, y in pts:
            d.ellipse([x - 70 * S, y - 10 * S, x + 70 * S, y + 120 * S], fill=col + (26,))
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(26 * S)))


if __name__ == "__main__":
    out = sys.argv[1]
    only = sys.argv[2:] or None
    for cfg in SCENES:
        if only and not any(o in cfg["name"] for o in only): continue
        if cfg.get("aurora"):
            orig = stars_moon
            def sm(img, rnd, *a, _o=orig):
                _o(img, rnd, *a); aurora(img)
            globals()["stars_moon"] = sm
            scene(cfg, out)
            globals()["stars_moon"] = orig
        else:
            scene(cfg, out)
        print("wrote", cfg["name"])

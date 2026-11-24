import sys, os
# Regenerates the launcher icon: python3 tools/make_icon.py app/src/main/res /tmp/preview.png (needs Pillow).
#
# The icon is a little device: a moulded dark-grey plastic bezel with a recessed display-glass
# screen in it (fine scanlines, vignette, a diagonal reflection), showing a glowing blue
# hourglass with sand still running.
# Everything is drawn on a 108dp adaptive-icon canvas. Launchers show only the middle ~72dp,
# cut to a circle, squircle or square, so the bezel runs out to the edge and the screen and
# digits stay inside the safe zone.
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

FONT = os.path.expanduser("~/.fonts/Inter-VariableFont_slnt,wght.ttf")
TEXT = "13:37"
CORE = (200, 238, 255)   # near-white blue core
GLOW = (33, 150, 243)    # #2196F3
DEEP = (0, 90, 255)
PLASTIC = (58, 60, 67)

U = 16                    # pixels per dp in the master image
S = 108 * U               # master canvas
# The screen: a portrait rounded rectangle, centred, safe inside a 72dp circle.
SCREEN = (S / 2 - 20 * U, S / 2 - 28 * U, S / 2 + 20 * U, S / 2 + 28 * U)
SCREEN_R = 8 * U


def rounded_mask(box, radius, blur=0):
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle(box, radius=radius, fill=255)
    return m.filter(ImageFilter.GaussianBlur(blur)) if blur else m


def vgradient(top, bottom):
    """A full-canvas vertical gradient between two RGBA colours."""
    g = Image.linear_gradient("L").resize((S, S))
    a = Image.new("RGBA", (S, S), top)
    b = Image.new("RGBA", (S, S), bottom)
    return Image.composite(b, a, g)


def radial(center, radius, inner, outer):
    """A radial gradient image from `inner` at `center` to `outer` at `radius`."""
    g = Image.radial_gradient("L")  # 256x256, 0 in the middle, 255 at the edge
    size = int(radius * 2)
    g = g.resize((size, size))
    full = Image.new("L", (S, S), 255)
    full.paste(g, (int(center[0] - radius), int(center[1] - radius)))
    a = Image.new("RGBA", (S, S), inner)
    b = Image.new("RGBA", (S, S), outer)
    return Image.composite(b, a, full)


def text_mask(width_frac, size=S):
    """White-on-transparent '13:37', centred, `width_frac` of the canvas wide."""
    lo, hi = 10, size
    while hi - lo > 1:  # biggest font that fits
        mid = (lo + hi) // 2
        f = ImageFont.truetype(FONT, mid)
        f.set_variation_by_axes([600, 0])  # wght, slnt
        l, t, r, b = f.getbbox(TEXT)
        if r - l <= size * width_frac: lo = mid
        else: hi = mid
    f = ImageFont.truetype(FONT, lo)
    f.set_variation_by_axes([600, 0])
    l, t, r, b = f.getbbox(TEXT)
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).text(((size - (r - l)) / 2 - l, (size - (b - t)) / 2 - t), TEXT, font=f, fill=255)
    return m


def background():
    # Moulded plastic: lit from above, rolling into shade below, a little brighter in the middle.
    img = vgradient(tuple(min(255, int(c * 1.45)) for c in PLASTIC) + (255,), tuple(int(c * 0.45) for c in PLASTIC) + (255,))
    img = Image.alpha_composite(img, radial((S / 2, S * 0.38), S * 0.55, (255, 255, 255, 40), (0, 0, 0, 0)))
    # Soft falloff towards wherever the launcher's mask cuts the edge.
    img = Image.alpha_composite(img, radial((S / 2, S / 2), S * 0.42, (0, 0, 0, 0), (0, 0, 0, 120)))

    # The screen's recess: a shadow cast by the top lip, a lit lower lip.
    lip = 2.2 * U
    x0, y0, x1, y1 = SCREEN
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    shadow.putalpha(rounded_mask((x0 - lip, y0 - lip, x1 + lip, y1 + lip), SCREEN_R + lip, blur=U * 0.8).point(lambda v: v * 0.9))
    img = Image.alpha_composite(img, shadow)
    lit = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    lower = ImageChops.subtract(
        rounded_mask((x0 - lip * 0.6, y0, x1 + lip * 0.6, y1 + lip * 0.9), SCREEN_R + lip * 0.6),
        rounded_mask((x0 - lip * 0.6, y0 - lip, x1 + lip * 0.6, y1 + lip * 0.2), SCREEN_R + lip * 0.6),
    )
    lit.putalpha(lower.filter(ImageFilter.GaussianBlur(U * 0.4)).point(lambda v: v * 0.35))
    img = Image.alpha_composite(img, lit)

    # The screen itself: near-black glass, faintly blue, darker at the edges.
    screen = vgradient((3, 4, 6, 255), (10, 14, 20, 255))
    screen = Image.alpha_composite(screen, radial((S / 2, S / 2), 34 * U, (12, 30, 52, 255), (0, 0, 0, 0)))
    # Fine scanlines, every 0.75dp, barely there.
    lines = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(lines)
    step = int(0.75 * U)
    for y in range(0, S, step):
        d.rectangle((0, y, S, y + step // 3), fill=(0, 0, 0, 70))
    screen = Image.alpha_composite(screen, lines)
    # Inner shadow along the top edge of the glass.
    inner = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    inner.putalpha(ImageChops.subtract(
        rounded_mask(SCREEN, SCREEN_R),
        rounded_mask((x0, y0 + 1.6 * U, x1, y1 + 1.6 * U), SCREEN_R),
    ).filter(ImageFilter.GaussianBlur(U * 0.9)).point(lambda v: v * 0.9))
    screen = Image.alpha_composite(screen, inner)
    img.paste(screen, (0, 0), rounded_mask(SCREEN, SCREEN_R))
    return img


def hourglass_mask():
    """The hourglass in white: angular frame, straight glass, and sand (some still in the top)."""
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    cx, cy = S / 2, S / 2
    H, W = 42 * U, 26 * U          # glass height and width
    line = 2.2 * U

    top, bottom = cy - H / 2, cy + H / 2
    # Glass: two straight-sided triangles meeting at the neck.
    d.line([(cx - W / 2 + U, top), (cx, cy), (cx - W / 2 + U, bottom)], fill=255, width=int(line), joint="curve")
    d.line([(cx + W / 2 - U, top), (cx, cy), (cx + W / 2 - U, bottom)], fill=255, width=int(line), joint="curve")
    # Flat caps.
    cap_h = 2.8 * U
    for y in (top, bottom):
        d.rectangle((cx - W / 2 - 1.5 * U, y - cap_h / 2, cx + W / 2 + 1.5 * U, y + cap_h / 2), fill=255)

    # Inside width of the glass at height y (straight sides, narrowing to the neck).
    def inner(y):
        return max(0.0, (W / 2 - U) * abs(y - cy) / (H / 2) - line * 1.2)

    # Sand left in the top: the lower part of the upper triangle, a flat surface on top.
    level = cy - H * 0.33
    d.polygon([(cx - inner(level), level), (cx + inner(level), level), (cx, cy - line * 0.6)], fill=255)
    # A thin stream through the neck, and a small pile on the bottom.
    d.line([(cx, cy), (cx, bottom - 7 * U)], fill=210, width=int(0.8 * U))
    floor = bottom - cap_h / 2 - 0.6 * U
    base = inner(floor)
    d.polygon([(cx - base, floor), (cx, floor - 7.5 * U), (cx + base, floor)], fill=255)
    return m


def digits():
    """The glowing hourglass on its glass (kept as `digits` for the layer it fills)."""
    m = hourglass_mask()
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for radius, colour, strength in [(S * 0.07, DEEP, 1.2), (S * 0.03, GLOW, 1.5), (S * 0.01, GLOW, 1.8)]:
        a = m.filter(ImageFilter.GaussianBlur(radius)).point(lambda v: min(255, int(v * strength)))
        layer = Image.new("RGBA", (S, S), colour + (0,))
        layer.putalpha(a)
        out = Image.alpha_composite(out, layer)
    core = Image.new("RGBA", (S, S), CORE + (0,))
    core.putalpha(m)
    out = Image.alpha_composite(out, core)

    # Glass reflection: a soft diagonal sheen across the top of the screen, over the digits.
    sheen = Image.new("L", (S, S), 0)
    x0, y0, x1, y1 = SCREEN
    ImageDraw.Draw(sheen).polygon([(x0, y0), (x1, y0), (x1, y0 + 8 * U), (x0, y0 + 26 * U)], fill=40)
    sheen = sheen.filter(ImageFilter.GaussianBlur(U * 1.5))
    sheen = ImageChops.multiply(sheen, rounded_mask(SCREEN, SCREEN_R))
    gloss = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    gloss.putalpha(sheen)
    return Image.alpha_composite(out, gloss)


res = sys.argv[1]
bg = background()
fg = digits()
mono = Image.new("RGBA", (S, S), (255, 255, 255, 0))
mono.putalpha(hourglass_mask())
full = Image.alpha_composite(bg, fg)
# Legacy (Android 5-7) icon: the visible middle 72dp as a rounded square.
crop = full.crop((18 * U, 18 * U, 90 * U, 90 * U))
corner = Image.new("L", crop.size, 0)
ImageDraw.Draw(corner).rounded_rectangle((0, 0, crop.size[0] - 1, crop.size[1] - 1), radius=crop.size[0] * 0.2, fill=255)
crop.putalpha(corner)

for name, scale in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
    d = f"{res}/mipmap-{name}"
    os.makedirs(d, exist_ok=True)
    px = int(108 * scale)
    bg.resize((px, px), Image.LANCZOS).save(f"{d}/ic_launcher_bg.png")
    fg.resize((px, px), Image.LANCZOS).save(f"{d}/ic_launcher_fg.png")
    mono.resize((px, px), Image.LANCZOS).save(f"{d}/ic_launcher_mono.png")
    lg = int(48 * scale)
    crop.resize((lg, lg), Image.LANCZOS).save(f"{d}/ic_launcher.png")

# A preview as a circle-masked launcher would show it.
preview = full.crop((18 * U, 18 * U, 90 * U, 90 * U)).resize((512, 512), Image.LANCZOS)
circle = Image.new("L", (512, 512), 0)
ImageDraw.Draw(circle).ellipse((0, 0, 511, 511), fill=255)
out = Image.new("RGBA", (512, 512), (40, 40, 40, 255))
out.paste(preview, (0, 0), circle)
out.save(sys.argv[2])

#!/usr/bin/env python3
"""Generate a launcher icon PNG (pure stdlib, no PIL)."""
import struct, zlib, os

SIZE = 192

# 5x7 bitmap glyphs for "DSH"
GLYPHS = {
    'D': ["01110","10001","10001","10001","10001","10001","01110"],
    'S': ["01111","10000","10000","01110","00001","00001","11110"],
    'H': ["10001","10001","10001","11111","10001","10001","10001"],
}
GAP = 1  # 1 column gap between letters

BG_TOP = (11, 18, 32)      # #0b1220
BG_BOT = (22, 35, 59)      # #16233b
FG = (34, 211, 238)        # #22d3ee cyan
ACCENT = (129, 140, 248)   # #818cf8 indigo

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def rounded_rect_px(x, y, w, h, r):
    def in_circle(px, py, cx, cy):
        return (px-cx)**2 + (py-cy)**2 <= r*r
    if x < r and y < r:
        return in_circle(x, y, r, r)
    if x >= w-r and y < r:
        return in_circle(x, y, w-r, r)
    if x < r and y >= h-r:
        return in_circle(x, y, r, h-r)
    if x >= w-r and y >= h-r:
        return in_circle(x, y, w-r, h-r)
    return True

cells = []
for ch in "DSH":
    for row in GLYPHS[ch]:
        cells.append(row)
    cells.append("0" * GAP)
cells = cells[:-1]

rows = len(cells)
cols = max(len(r) for r in cells)
CELL = 6
gw, gh = cols * CELL, rows * CELL
ox, oy = (SIZE - gw) // 2, (SIZE - gh) // 2

radius = 42
raw = bytearray()
for y in range(SIZE):
    raw.append(0)  # filter: none
    for x in range(SIZE):
        if rounded_rect_px(x, y, SIZE-1, SIZE-1, radius):
            t = y / (SIZE - 1)
            c = lerp(BG_TOP, BG_BOT, t)
        else:
            c = (0, 0, 0)
        gx, gy = x - ox, y - oy
        if 0 <= gx < gw and 0 <= gy < gh:
            col = gx // CELL
            row = gy // CELL
            if row < rows and col < len(cells[row]) and cells[row][col] == '1':
                c = FG
        if y > SIZE - 26 and rounded_rect_px(x, y, SIZE-1, SIZE-1, radius):
            if abs(x - SIZE//2) < 48 and abs(y - (SIZE-14)) < 6:
                c = ACCENT
        raw += bytes(c + (255,))

def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    c += struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    return c

png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
png += chunk(b"IEND", b"")

os.makedirs("res/mipmap", exist_ok=True)
with open("res/mipmap/ic_launcher.png", "wb") as f:
    f.write(png)
print("icon written:", len(png), "bytes")

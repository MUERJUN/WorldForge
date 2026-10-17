"""Quantitative sharpness check for the WorldForge Studio screenshots.

I cannot view images with the active model, so "is the custom UI still
blurry?" is answered numerically: decode the PNG (stdlib only), compute a
Laplacian-variance sharpness metric over the panel region, and compare it
against a deliberately blurred copy of the same region. A crisp screenshot
loses most of its edge energy when blurred; an already-blurry one does not.
"""
import io
import struct
import sys
import zlib


def read_png(path):
    data = io.open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a png'
    pos = 8
    w = h = None
    bitdepth = colortype = None
    idat = []
    while pos < len(data):
        (length,) = struct.unpack('>I', data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b'IHDR':
            w, h, bitdepth, colortype = struct.unpack('>IIBB', chunk[:10])
        elif ctype == b'IDAT':
            idat.append(chunk)
        elif ctype == b'IEND':
            break
    assert bitdepth == 8, 'expected 8-bit'
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[colortype]
    raw = zlib.decompress(b''.join(idat))
    stride = w * channels
    out = bytearray(w * h * channels)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, channels, out


def luma(w, h, ch, buf, x0, y0, x1, y1):
    """grayscale plane of a region"""
    plane = []
    stride = w * ch
    for y in range(y0, y1):
        row = []
        base = y * stride
        for x in range(x0, x1):
            o = base + x * ch
            r, g, b = buf[o], buf[o + 1], buf[o + 2]
            row.append((r * 299 + g * 587 + b * 114) // 1000)
        plane.append(row)
    return plane


def laplacian_variance(plane):
    """standard focus metric: variance of the 4-neighbour Laplacian"""
    h = len(plane)
    w = len(plane[0])
    vals = []
    for y in range(1, h - 1):
        for x in range(1, w - 1):
            lap = 4 * plane[y][x] - plane[y - 1][x] - plane[y + 1][x] - plane[y][x - 1] - plane[y][x + 1]
            vals.append(lap)
    if not vals:
        return 0.0
    mean = sum(vals) / len(vals)
    return sum((v - mean) ** 2 for v in vals) / len(vals)


def box_blur(plane, radius=2):
    h = len(plane)
    w = len(plane[0])
    out = [[0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            s = 0
            n = 0
            for dy in range(-radius, radius + 1):
                yy = y + dy
                if 0 <= yy < h:
                    for dx in range(-radius, radius + 1):
                        xx = x + dx
                        if 0 <= xx < w:
                            s += plane[yy][xx]
                            n += 1
            out[y][x] = s // n
    return out


def main():
    for path in sys.argv[1:]:
        w, h, ch, buf = read_png(path)
        # The WorldForge panel is centred; sample a band that contains the
        # title, the item list and the AI text area (the regions users read).
        # sample strictly INSIDE the WorldForge panel (the card is centred and
        # occupies roughly the middle half of the window); sampling further out
        # would mix in the intentionally blurred game background.
        x0, y0 = int(w * 0.32), int(h * 0.35)
        x1, y1 = int(w * 0.68), int(h * 0.70)
        plane = luma(w, h, ch, buf, x0, y0, x1, y1)
        crisp = laplacian_variance(plane)
        blurred = laplacian_variance(box_blur(plane, 2))
        ratio = (crisp / blurred) if blurred > 0 else float('inf')
        print('%-46s %4dx%-4d panel-edge-energy=%9.1f blurred=%9.1f loss-ratio=%.2f'
              % (path.split('/')[-1].split('\\')[-1], w, h, crisp, blurred, ratio))


if __name__ == '__main__':
    main()
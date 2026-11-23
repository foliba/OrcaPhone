#!/usr/bin/env python3
"""
Builds app/src/main/assets/beds from OrcaSlicer's resources/profiles: the bed model (STL) and bed texture
(SVG/PNG) of every printer model that has them, plus index.json (printer model name -> files).

Bed models are stored in a compact format (.pbed), little endian:
  "PBED", u8 version (1), u8 flags (bit 0: 32-bit indices)
  f32 origin x, y, z; f32 step (grid size in mm)
  u32 vertex count, u32 triangle count
  vertices: u16 x, y, z  (position = origin + value * step)
  triangles: 3 indices each, u16 or u32
Vertices are shared and snapped to a 0.01 mm grid (coarser only for models over 655 mm); textures are
copied as they are. The APK compresses all of it.

Usage: tools/gen_bed_assets.py <OrcaSlicer>/resources/profiles [app/src/main/assets/beds]
"""
import glob
import json
import os
import shutil
import struct
import sys


def read_stl(path):
    data = open(path, "rb").read()
    tris = []
    if len(data) >= 84:
        n = struct.unpack_from("<I", data, 80)[0]
        if 84 + n * 50 == len(data):
            for i in range(n):
                v = struct.unpack_from("<9f", data, 84 + i * 50 + 12)
                tris.append((v[0:3], v[3:6], v[6:9]))
            return tris
    # ASCII STL
    verts = []
    for line in data.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if len(parts) == 4 and parts[0] == "vertex":
            verts.append(tuple(float(x) for x in parts[1:]))
            if len(verts) == 3:
                tris.append(tuple(verts))
                verts = []
    return tris


def encode(tris):
    xs = [p[0] for t in tris for p in t]
    ys = [p[1] for t in tris for p in t]
    zs = [p[2] for t in tris for p in t]
    origin = (min(xs), min(ys), min(zs))
    extent = max(max(xs) - origin[0], max(ys) - origin[1], max(zs) - origin[2])
    step = max(0.01, extent / 65000.0)
    index = {}
    verts = []
    out_tris = []
    for t in tris:
        ids = []
        for p in t:
            q = tuple(int(round((p[k] - origin[k]) / step)) for k in range(3))
            i = index.get(q)
            if i is None:
                i = index[q] = len(verts)
                verts.append(q)
            ids.append(i)
        if len(set(ids)) == 3:  # drop triangles that collapsed on the grid
            out_tris.append(ids)
    wide = len(verts) > 65535
    b = bytearray(b"PBED")
    b += struct.pack("<BB", 1, 1 if wide else 0)
    b += struct.pack("<4f", origin[0], origin[1], origin[2], step)
    b += struct.pack("<II", len(verts), len(out_tris))
    for q in verts:
        b += struct.pack("<3H", *q)
    fmt = "<3I" if wide else "<3H"
    for t in out_tris:
        b += struct.pack(fmt, *t)
    return bytes(b), len(verts), len(out_tris)


def main():
    root = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/beds"
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    models = {}
    stl_in = stl_out = 0
    for f in sorted(glob.glob(os.path.join(root, "*", "machine", "*.json"))):
        try:
            d = json.load(open(f, encoding="utf-8"))
        except Exception:
            continue
        if d.get("type") != "machine_model" or not d.get("name"):
            continue
        vendor_dir = os.path.dirname(os.path.dirname(f))
        vendor = os.path.basename(vendor_dir)
        entry = {}
        m = d.get("bed_model")
        if m and os.path.exists(os.path.join(vendor_dir, m)):
            src = os.path.join(vendor_dir, m)
            tris = read_stl(src)
            if tris:
                blob, nv, nt = encode(tris)
                name = f"{vendor}/{os.path.splitext(m)[0]}.pbed"
                os.makedirs(os.path.join(out, vendor), exist_ok=True)
                dst = os.path.join(out, name)
                if not os.path.exists(dst):
                    open(dst, "wb").write(blob)
                    stl_in += os.path.getsize(src)
                    stl_out += len(blob)
                entry["model"] = name
        t = d.get("bed_texture")
        if t and os.path.exists(os.path.join(vendor_dir, t)) and os.path.splitext(t)[1].lower() in (".svg", ".png"):
            name = f"{vendor}/{t}"
            os.makedirs(os.path.join(out, vendor), exist_ok=True)
            dst = os.path.join(out, name)
            if not os.path.exists(dst):
                shutil.copyfile(os.path.join(vendor_dir, t), dst)
            entry["texture"] = name
        if entry:
            models[d["name"]] = entry
    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as fp:
        json.dump({"version": 1, "source": "OrcaSlicer resources/profiles", "models": models}, fp, ensure_ascii=False, sort_keys=True, indent=0)
    print(f"{len(models)} printer models; bed models {stl_in / 1e6:.1f} MB STL -> {stl_out / 1e6:.1f} MB .pbed")


if __name__ == "__main__":
    main()

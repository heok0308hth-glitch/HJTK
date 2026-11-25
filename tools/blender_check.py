"""Open every .glb and .obj that HJTK 3D exported in real Blender and compare them.

    pip install bpy            # Blender as a Python module
    ./gradlew :mesh:test       # writes build/mesh-out/*.glb|obj
    python3 tools/blender_check.py build/mesh-out

For each model: object count, triangle count and materials after import, and the largest
world-space bounding-box difference between the .glb (node hierarchy, quaternions) and the
.obj (baked world positions). 0.00000 means both files put every part in the same place.
"""
import os
import sys

import bpy


def load(path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    if path.endswith(".glb"):
        bpy.ops.import_scene.gltf(filepath=path)
    else:
        bpy.ops.wm.obj_import(filepath=path)
    out = {}
    for o in bpy.context.scene.objects:
        if o.type != "MESH":
            continue
        pts = [o.matrix_world @ v.co for v in o.data.vertices]
        lo = tuple(min(p[i] for p in pts) for i in range(3))
        hi = tuple(max(p[i] for p in pts) for i in range(3))
        tris = sum(len(pl.vertices) - 2 for pl in o.data.polygons)
        mats = {s.material.name for s in o.material_slots if s.material}
        out[o.name.split(".")[0]] = (lo, hi, tris, mats)
    return out


def main(d):
    bases = sorted({f.rsplit(".", 1)[0] for f in os.listdir(d) if f.endswith(".glb")})
    worst_all = 0.0
    for b in bases:
        g = load(os.path.join(d, b + ".glb"))
        o = load(os.path.join(d, b + ".obj"))
        worst = 0.0
        for k, (lo, hi, _, _) in g.items():
            if k in o:
                worst = max(worst, max(abs(a - c) for a, c in zip(lo + hi, o[k][0] + o[k][1])))
        worst_all = max(worst_all, worst)
        tris = sum(v[2] for v in g.values())
        mats = sorted(set().union(*[v[3] for v in g.values()])) if g else []
        print(f"{b:16s} objects {len(g):2d}/{len(o):2d}  tris {tris:6d}  materials {mats}  max bbox diff {worst:.5f} m")
    print("Blender", bpy.app.version_string)
    sys.exit(0 if worst_all < 1e-3 else 1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/mesh-out")

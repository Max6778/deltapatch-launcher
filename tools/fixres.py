#!/usr/bin/env python3
"""
The base.apk pulled from an installed app is only the *base split*: the density-specific launcher
icons live in other split APKs, so apktool decodes mipmap entries as bare path strings that aapt2
refuses to compile ("invalid value for type 'mipmap'. Expected a reference").

This removes those unusable entries so the rebuild can succeed:
  - deletes res/values*/mipmaps.xml
  - drops the matching <public type="mipmap"> lines from res/values*/public.xml
  - removes android:icon / roundIcon / banner / logo attributes that point to them
(the rebuilt app simply gets the default Android icon)

usage: fixres.py <decoded_dir>
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID
ET.register_namespace("android", ANDROID)


def main(dec: pathlib.Path) -> None:
    gone = set()
    for f in dec.glob("res/values*/mipmaps.xml"):
        try:
            for el in ET.parse(f).getroot():
                if el.get("name"):
                    gone.add(el.get("name"))
        except ET.ParseError:
            pass
        f.unlink()
        print("removed", f.relative_to(dec))
    if not gone:
        print("no broken mipmap files, nothing to do")
        return

    for f in dec.glob("res/values*/public.xml"):
        txt = f.read_text(encoding="utf-8")
        n = 0
        out = []
        for line in txt.split("\n"):
            m = re.search(r'<public\s+type="mipmap"\s+name="([^"]+)"', line)
            if m and m.group(1) in gone:
                n += 1
                continue
            out.append(line)
        if n:
            f.write_text("\n".join(out), encoding="utf-8")
            print("public.xml: dropped %d mipmap ids in %s" % (n, f.relative_to(dec)))

    mf = dec / "AndroidManifest.xml"
    tree = ET.parse(mf)
    changed = 0
    for el in tree.getroot().iter():
        for attr in ("icon", "roundIcon", "banner", "logo"):
            v = el.get(A + attr)
            if v and v.startswith("@mipmap/") and v.split("/", 1)[1] in gone:
                del el.attrib[A + attr]
                changed += 1
    if changed:
        tree.write(mf, encoding="utf-8", xml_declaration=True)
    print("manifest: removed %d icon reference(s)" % changed)

    # warn about any other leftover references
    left = 0
    for f in list(dec.glob("res/**/*.xml")):
        try:
            t = f.read_text(encoding="utf-8")
        except Exception:
            continue
        for n in gone:
            if "@mipmap/" + n in t:
                print("WARNING: %s still references @mipmap/%s" % (f.relative_to(dec), n))
                left += 1
    print("done; %d leftover reference(s)" % left)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(pathlib.Path(sys.argv[1]))

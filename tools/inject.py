#!/usr/bin/env python3
"""
Edits an apktool-decoded DELTARUNE port so it boots into our launcher.

usage: inject.py <decoded_dir> <new_package>

 1. AndroidManifest.xml
      - expands relative class names (so renaming the package keeps classes working)
      - renames the application package (permissions/authorities follow, class names do NOT,
        because libyoyo.so finds com/hadrian/deltarune/* classes by name)
      - removes LAUNCHER from RunnerActivity, adds our LauncherActivity as LAUNCHER
      - registers SavesActivity and SaveEditorActivity
 2. smali: WADLoader.ExtractAssetExt gets a hook at its start so the game does not
    overwrite a patched chapter package with the original copy.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID
ET.register_namespace("android", ANDROID)
ET.register_namespace("app", "http://schemas.android.com/apk/res-auto")
ET.register_namespace("tools", "http://schemas.android.com/tools")

LAUNCHER_CLASS = "com.deltapatch.launcher.LauncherActivity"
CLASS_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


def patch_manifest(dec: pathlib.Path, new_pkg: str) -> None:
    mf = dec / "AndroidManifest.xml"
    tree = ET.parse(mf)
    root = tree.getroot()
    old = root.get("package")
    if not old:
        sys.exit("manifest has no package attribute")
    if new_pkg == old:
        sys.exit("new package must differ from the original (%s): refusing, it would replace your installed game" % old)
    app = root.find("application")

    def absolute(n: str) -> str:
        if n.startswith("."):
            return old + n
        if "." not in n:
            return old + "." + n
        return n

    if app.get(A + "name"):
        app.set(A + "name", absolute(app.get(A + "name")))
    for el in app:
        if el.tag in CLASS_TAGS and el.get(A + "name"):
            el.set(A + "name", absolute(el.get(A + "name")))
        if el.get(A + "targetActivity"):
            el.set(A + "targetActivity", absolute(el.get(A + "targetActivity")))

    # permissions / authorities that embed the package must follow the rename
    for el in root.iter():
        if el.tag in ("permission", "uses-permission", "uses-permission-sdk-23"):
            n = el.get(A + "name", "")
            if n.startswith(old + "."):
                el.set(A + "name", new_pkg + n[len(old):])
        for attr in ("authorities", "permission", "readPermission", "writePermission"):
            v = el.get(A + attr)
            if v and old in v:
                el.set(A + attr, v.replace(old, new_pkg))
        if el.tag == "provider" and el.get(A + "authorities") and new_pkg not in el.get(A + "authorities"):
            el.set(A + "authorities", el.get(A + "authorities") + ".dp")

    # RunnerActivity is no longer the launcher entry
    for act in app.findall("activity"):
        if act.get(A + "name", "").endswith(".RunnerActivity"):
            for f in act.findall("intent-filter"):
                for cat in list(f.findall("category")):
                    if cat.get(A + "name") in ("android.intent.category.LAUNCHER",
                                               "android.intent.category.LEANBACK_LAUNCHER"):
                        f.remove(cat)

    cfg = "orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout"
    theme = "@android:style/Theme.DeviceDefault.NoActionBar"

    def add(name, launcher):
        el = ET.SubElement(app, "activity")
        el.set(A + "name", name)
        el.set(A + "exported", "true" if launcher else "false")
        el.set(A + "theme", theme)
        el.set(A + "configChanges", cfg)
        if launcher:
            f = ET.SubElement(el, "intent-filter")
            ET.SubElement(f, "action").set(A + "name", "android.intent.action.MAIN")
            ET.SubElement(f, "category").set(A + "name", "android.intent.category.LAUNCHER")
            ET.SubElement(f, "category").set(A + "name", "android.intent.category.LEANBACK_LAUNCHER")

    names = {a.get(A + "name") for a in app.findall("activity")}
    if LAUNCHER_CLASS not in names:
        add(LAUNCHER_CLASS, True)
        add("com.deltapatch.launcher.SavesActivity", False)
        add("com.deltapatch.launcher.SaveEditorActivity", False)
        add("com.deltapatch.launcher.MakeActivity", False)

    app.set(A + "largeHeap", "true")
    app.set(A + "requestLegacyExternalStorage", "true")
    have = {e.get(A + "name") for e in root.findall("uses-permission")}
    first_app = list(root).index(app)
    added = 0
    for perm in ("android.permission.INTERNET",
                              "android.permission.RECORD_AUDIO",
                              "android.permission.READ_EXTERNAL_STORAGE",
                              "android.permission.WRITE_EXTERNAL_STORAGE",
                              "android.permission.MANAGE_EXTERNAL_STORAGE",
                              "com.termux.permission.RUN_COMMAND"):
        if perm not in have:
            el = ET.Element("uses-permission")
            el.set(A + "name", perm)
            root.insert(first_app + added, el)
            added += 1
    first_app += added
    # distinct name under the icon so it can't be confused with the installed original
    app.set(A + "label", "DELTARUNE Patch")
    for act in app.findall("activity"):
        if A + "label" in act.attrib:
            del act.attrib[A + "label"]
    if root.find("queries") is None:
        q = ET.Element("queries")
        ET.SubElement(q, "package").set(A + "name", "com.termux")
        root.insert(first_app, q)
    root.set("package", new_pkg)
    tree.write(mf, encoding="utf-8", xml_declaration=True)
    print("manifest: %s -> %s" % (old, new_pkg))


HOOK = (
    "    invoke-static {p0, p1}, Lcom/deltapatch/launcher/Hook;->isPatched(Ljava/lang/Object;Ljava/lang/String;)Z\n"
    "\n    move-result v0\n\n    if-eqz v0, :dp_continue\n\n"
    "    const-wide/high16 v0, 0x3ff0000000000000L\n\n    return-wide v0\n\n    :dp_continue\n"
)


def patch_smali(dec: pathlib.Path) -> None:
    files = list(dec.glob("smali*/com/hadrian/deltarune/WADLoader.smali"))
    if not files:
        sys.exit("WADLoader.smali not found - is this the right APK?")
    f = files[0]
    lines = f.read_text(encoding="utf-8").split("\n")
    if any("Lcom/deltapatch/launcher/Hook;" in l for l in lines):
        print("smali: already hooked")
        return
    start = None
    for i, l in enumerate(lines):
        if l.startswith(".method") and "ExtractAssetExt(Ljava/lang/String;)D" in l:
            start = i
            break
    if start is None:
        sys.exit("ExtractAssetExt(String)D not found in WADLoader.smali - the port changed, inspect it first")
    j = start + 1
    # locals / registers directive
    while not re.match(r"\s*\.(locals|registers)\s+\d+", lines[j]):
        j += 1
        if lines[j].startswith(".end method"):
            sys.exit("no .locals in ExtractAssetExt")
    m = re.match(r"\s*\.(locals|registers)\s+(\d+)", lines[j])
    n = int(m.group(2))
    if m.group(1) == "registers":
        n -= 2  # this + one parameter
    if n < 2:
        sys.exit("ExtractAssetExt needs 2 free registers for the hook")
    k = j + 1
    while lines[k].strip() == "" or lines[k].lstrip().startswith("."):
        if lines[k].startswith(".end method"):
            sys.exit("empty method")
        k += 1
    lines[k:k] = HOOK.rstrip("\n").split("\n")
    f.write_text("\n".join(lines), encoding="utf-8")
    print("smali: hooked %s line %d" % (f.name, k))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    d = pathlib.Path(sys.argv[1])
    patch_manifest(d, sys.argv[2])
    patch_smali(d)

using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using UndertaleModLib;
using UndertaleModLib.Compiler;
using UndertaleModLib.Decompiler;
using UndertaleModLib.Models;

// Turns a PC (Steam) game file - vanilla or already mod-patched - into one that runs on Hadrian's Android port:
//   1. copies the Android-only touch-control sprites (with their texture pages) from the port's own game file,
//   2. recompiles the Android-only scripts and objects (touch UI, virtual keys, joystick) into the PC file,
//   3. calls scr_init_touch_controls() at start-up,
//   4. fixes the texture pre-load that hangs on Android and points music at temp_directory/mus.
// Usage: UTMT_ANDROID=<port game file> UndertaleModCli load <pc file> -s AndroidPort.csx -o <out> -f

EnsureDataLoaded();

string androidPath = Environment.GetEnvironmentVariable("UTMT_ANDROID");
if (string.IsNullOrEmpty(androidPath) || !File.Exists(androidPath))
    throw new Exception("UTMT_ANDROID must point to the Android port's game file");

Console.WriteLine("Loading the Android file: " + androidPath);
UndertaleData A;
using (MemoryStream ms = new MemoryStream(File.ReadAllBytes(androidPath)))
{
    A = UndertaleIO.Read(ms);
}
Console.WriteLine("Android file: " + A.Sprites.Count + " sprites, " + A.Code.Count + " code entries");
Console.WriteLine("Target file:  " + Data.Sprites.Count + " sprites, " + Data.Code.Count + " code entries");

// ---------------------------------------------------------------- 1. sprites (copy the texture pages as they are)
Dictionary<UndertaleEmbeddedTexture, UndertaleEmbeddedTexture> pageMap = new();
Dictionary<UndertaleTexturePageItem, UndertaleTexturePageItem> itemMap = new();

UndertaleTexturePageItem CopyItem(UndertaleTexturePageItem it)
{
    if (it == null) return null;
    if (itemMap.TryGetValue(it, out UndertaleTexturePageItem done)) return done;

    UndertaleEmbeddedTexture srcPage = it.TexturePage;
    if (!pageMap.TryGetValue(srcPage, out UndertaleEmbeddedTexture page))
    {
        page = new UndertaleEmbeddedTexture();
        page.Name = new UndertaleString("Texture " + Data.EmbeddedTextures.Count);
        page.Scaled = srcPage.Scaled;
        page.GeneratedMips = srcPage.GeneratedMips;
        page.TextureData.Image = srcPage.TextureData.Image;
        Data.EmbeddedTextures.Add(page);
        pageMap[srcPage] = page;
    }

    UndertaleTexturePageItem n = new UndertaleTexturePageItem();
    n.Name = new UndertaleString("PageItem " + Data.TexturePageItems.Count);
    n.SourceX = it.SourceX;
    n.SourceY = it.SourceY;
    n.SourceWidth = it.SourceWidth;
    n.SourceHeight = it.SourceHeight;
    n.TargetX = it.TargetX;
    n.TargetY = it.TargetY;
    n.TargetWidth = it.TargetWidth;
    n.TargetHeight = it.TargetHeight;
    n.BoundingWidth = it.BoundingWidth;
    n.BoundingHeight = it.BoundingHeight;
    n.TexturePage = page;
    Data.TexturePageItems.Add(n);
    itemMap[it] = n;
    return n;
}

List<UndertaleSprite> newSprites = A.Sprites.Where(s => s != null && Data.Sprites.ByName(s.Name.Content) == null).ToList();
Console.WriteLine("Android-only sprites: " + newSprites.Count);
foreach (UndertaleSprite s in newSprites)
{
    UndertaleSprite n = new UndertaleSprite();
    n.Name = Data.Strings.MakeString(s.Name.Content);
    n.Width = s.Width;
    n.Height = s.Height;
    n.MarginLeft = s.MarginLeft;
    n.MarginRight = s.MarginRight;
    n.MarginTop = s.MarginTop;
    n.MarginBottom = s.MarginBottom;
    n.Transparent = s.Transparent;
    n.Smooth = s.Smooth;
    n.Preload = s.Preload;
    n.BBoxMode = s.BBoxMode;
    n.SepMasks = s.SepMasks;
    n.OriginX = s.OriginX;
    n.OriginY = s.OriginY;
    n.SVersion = s.SVersion;
    n.SSpriteType = s.SSpriteType;
    n.GMS2PlaybackSpeed = s.GMS2PlaybackSpeed;
    n.GMS2PlaybackSpeedType = s.GMS2PlaybackSpeedType;
    n.IsSpecialType = s.IsSpecialType;
    foreach (UndertaleSprite.TextureEntry te in s.Textures)
    {
        UndertaleSprite.TextureEntry ne = new UndertaleSprite.TextureEntry();
        ne.Texture = CopyItem(te?.Texture);
        n.Textures.Add(ne);
    }
    foreach (UndertaleSprite.MaskEntry m in s.CollisionMasks)
        n.CollisionMasks.Add(new UndertaleSprite.MaskEntry((byte[])m.Data.Clone(), m.Width, m.Height));
    Data.Sprites.Add(n);
    Console.WriteLine("  sprite " + n.Name.Content + " (" + n.Textures.Count + " frame(s))");
}
Console.WriteLine("Copied " + pageMap.Count + " texture page(s)");

// ---------------------------------------------------------------- 2. Android-only scripts and objects
GlobalDecompileContext ctxA = new GlobalDecompileContext(A);
List<string> newObjects = A.GameObjects.Where(o => o != null && Data.GameObjects.ByName(o.Name.Content) == null)
                                       .Select(o => o.Name.Content).ToList();

List<UndertaleCode> newEntries = A.Code.Where(c => c != null && c.Name != null && c.ParentEntry == null
        && Data.Code.ByName(c.Name.Content) == null
        && !c.Name.Content.Contains("load_wad")).ToList();   // load_wad needs the WADLoader extension, skipped
Console.WriteLine("Android-only code entries to import: " + newEntries.Count);

CodeImportGroup group = new CodeImportGroup(Data);
int failedDecompile = 0;
foreach (UndertaleCode c in newEntries.OrderBy(e => e.Name.Content.StartsWith("gml_Object_") ? 1 : 0))
{
    string text = GetDecompiledText(c, ctxA);
    if (text.StartsWith("/*\nDECOMPILER FAILED") || text.StartsWith("/*\r\nDECOMPILER FAILED"))
    {
        Console.WriteLine("DECOMPILE FAILED: " + c.Name.Content);
        failedDecompile++;
        continue;
    }
    group.QueueReplace(c.Name.Content, text);
}
if (failedDecompile > 0) throw new Exception(failedDecompile + " Android entries could not be decompiled");

// ---------------------------------------------------------------- 3 + 4. small edits of existing PC code
int queued = 0, missing = 0;

void RegexEdit(string codeName, string what, string pattern, string replacement)
{
    UndertaleCode code = Data.Code.ByName(codeName);
    if (code == null) { Console.WriteLine("MISSING ENTRY " + codeName); missing++; return; }
    string text = GetDecompiledText(code);
    if (!Regex.IsMatch(text, pattern, RegexOptions.None, TimeSpan.FromSeconds(10)))
    {
        Console.WriteLine("ANCHOR NOT FOUND " + codeName + " (" + what + ")");
        missing++;
        return;
    }
    group.QueueRegexFindReplace(codeName, pattern, replacement);
    Console.WriteLine("queued " + codeName + ": " + what);
    queued++;
}

RegexEdit("gml_Object_obj_initializer2_Create_0", "start the touch controls",
    @"(global\.is_console = scr_is_switch_os\(\) \|\| os_type == os_ps4 \|\| os_type == os_ps5;)",
    "$1\nscr_init_touch_controls();");

RegexEdit("gml_Object_obj_initializer2_Create_0", "texture prefetch that hangs on Android",
    @"if \(global\.is_console\)\s*\{\s*loadtex = instance_create\(0, 0, obj_prefetchtex\);\s*\}\s*else\s*\{\s*scr_prefetch_textures\(\);\s*\}",
    "loadtex = instance_create(0, 0, obj_prefetchtex);");

RegexEdit("gml_Object_obj_initializer2_Step_0", "wait for the textures on every platform",
    @"\s*if \(!textures_loaded\)\s*\{\s*textures_loaded = loadtex\.loaded;\s*\}\s*if \(textures_loaded\)\s*\{\s*show_debug_message_concat\(""TEXTURES LOADED""\);\s*\}\s*else\s*\{\s*exit;\s*\}\s*\}",
    "\n}\nif (!textures_loaded)\n{\n    textures_loaded = loadtex.loaded;\n}\nif (textures_loaded)\n{\n    show_debug_message_concat(\"TEXTURES LOADED\");\n}\nelse\n{\n    exit;\n}");

RegexEdit("gml_Object_obj_gamecontroller_Other_75", "do not crash when an async system event has no text type",
    @"show_debug_message\(""\*\*\*\*\* Event = "" \+ ds_map_find_value\(async_load, ""event_type""\)\);",
    "show_debug_message(\"***** Event = \" + string(ds_map_find_value(async_load, \"event_type\")));");

RegexEdit("gml_GlobalScript_snd_init", "music folder on Android",
    @"initsongvar = dir \+ arg0;",
    "if (os_type == os_android)\n    {\n        dir = temp_directory + \"mus/\";\n    }\n    initsongvar = dir + arg0;");

Console.WriteLine("Compiling " + (newEntries.Count + queued) + " code operations...");
group.Import();   // throws with the compiler messages on failure

// object settings (depth, persistence, ...) from the Android file
foreach (string name in newObjects)
{
    UndertaleGameObject src = A.GameObjects.ByName(name);
    UndertaleGameObject dst = Data.GameObjects.ByName(name);
    if (src == null || dst == null) { Console.WriteLine("object not created: " + name); continue; }
    dst.Visible = src.Visible;
    dst.Managed = src.Managed;
    dst.Solid = src.Solid;
    dst.Depth = src.Depth;
    dst.Persistent = src.Persistent;
    if (src.Sprite != null) dst.Sprite = Data.Sprites.ByName(src.Sprite.Name.Content);
    Console.WriteLine("  object " + name + " (depth " + dst.Depth + ")");
}

Console.WriteLine("DONE: sprites " + newSprites.Count + ", code entries " + newEntries.Count
    + ", edits queued " + queued + ", anchors missing " + missing);
if (missing > 0) Console.WriteLine("WARNING: some anchors were not found - the PC file may differ from what this script expects");

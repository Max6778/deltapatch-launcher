using System;
using System.Text.RegularExpressions;
using UndertaleModLib;
using UndertaleModLib.Compiler;
using UndertaleModLib.Decompiler;
using UndertaleModLib.Models;

// Fixes the APK's own game file (assets/game.droid, the chapter-select shell that runs first):
// its async system event handler crashes with "DoAdd :1: undefined value" when Android sends an event
// without a text type (permission dialog closing, controller or Bluetooth change).
// Usage: UndertaleModCli load assets/game.droid -s FixShell.csx -o fixed.droid -f

EnsureDataLoaded();

CodeImportGroup group = new CodeImportGroup(Data);
string name = "gml_Object_obj_gamecontroller_Other_75";
UndertaleCode code = Data.Code.ByName(name);
if (code == null) throw new Exception("MISSING ENTRY " + name);

string text = GetDecompiledText(code);
string pattern = @"show_debug_message\(""\*\*\*\*\* Event = "" \+ ds_map_find_value\(async_load, ""event_type""\)\);";
if (text.Contains("string(ds_map_find_value(async_load, \"event_type\"))"))
{
    Console.WriteLine("ALREADY FIXED: " + name);
    return;
}
if (!Regex.IsMatch(text, pattern, RegexOptions.None, TimeSpan.FromSeconds(10)))
    throw new Exception("ANCHOR NOT FOUND in " + name);

group.QueueRegexFindReplace(name, pattern,
    "show_debug_message(\"***** Event = \" + string(ds_map_find_value(async_load, \"event_type\")));");
group.Import();
Console.WriteLine("DONE: fixed " + name);

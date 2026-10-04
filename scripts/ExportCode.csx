using System;
using System.IO;
using UndertaleModLib.Models;

// Exports every code entry as decompiled GML into the folder given by the UTMT_OUT environment variable.
string outDir = Environment.GetEnvironmentVariable("UTMT_OUT");
if (string.IsNullOrEmpty(outDir)) throw new Exception("UTMT_OUT is not set");
Directory.CreateDirectory(outDir);
int ok = 0, bad = 0;
foreach (UndertaleCode code in Data.Code)
{
    if (code.ParentEntry != null) continue;
    string name = code.Name.Content;
    string text;
    try { text = GetDecompiledText(code); ok++; }
    catch (Exception e) { text = "// DECOMPILE FAILED: " + e.Message; bad++; }
    File.WriteAllText(Path.Combine(outDir, name + ".gml"), text);
}
Console.WriteLine("exported " + ok + " code entries, " + bad + " failed");

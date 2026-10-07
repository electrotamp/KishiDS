using System.IO.Compression;
using System.Runtime.InteropServices;

namespace KishiDS.Core;

/// <summary>
/// Locating the official v2.70 image used to restore the Kishi to its original firmware.  The app does not
/// bundle Razer's image: it is imported from a backup file or from the user's own copy of the Razer app (APK).
/// </summary>
public static class StockFirmware
{
    /// <summary>Developer/test aid (--stockdir): keep the imported image somewhere else.</summary>
    public static string? DirectoryOverride { get; set; }

    public static string StoreDirectory => DirectoryOverride ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KishiDS", "stock");

    public static string StorePath => Path.Combine(StoreDirectory, "kishi-0290-stock-v2.70.bin");

    /// <summary>The imported stock image, if present and genuine.</summary>
    public static byte[]? LoadStored()
    {
        try
        {
            if (!File.Exists(StorePath)) return null;
            var data = File.ReadAllBytes(StorePath);
            return FirmwareImage.IsOfficialStock(data) ? data : null;
        }
        catch { return null; }
    }

    /// <summary>Validate and keep a stock image from a .bin file or a Razer APK.  Returns an error message, or null on success.</summary>
    public static string? Import(string path)
    {
        var data = Extract(path, out var error);
        if (data is null) return error;
        try
        {
            Directory.CreateDirectory(StoreDirectory);
            File.WriteAllBytes(StorePath, data);
            return null;
        }
        catch (Exception ex) { return "Could not save the firmware: " + ex.Message; }
    }

    /// <summary>The official v2.70 image inside a .bin / .apk, or null with a plain-language reason.</summary>
    public static byte[]? Extract(string path, out string error)
    {
        error = "";
        try
        {
            if (path.EndsWith(".apk", StringComparison.OrdinalIgnoreCase) || path.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
            {
                using var zip = ZipFile.OpenRead(path);
                foreach (var e in zip.Entries.Where(e => e.FullName.EndsWith(".bin", StringComparison.OrdinalIgnoreCase) && e.Length == FirmwareImage.StockLength))
                {
                    using var s = e.Open();
                    using var ms = new MemoryStream();
                    s.CopyTo(ms);
                    if (FirmwareImage.IsOfficialStock(ms.ToArray())) return ms.ToArray();
                }
                error = "That file doesn't contain the Kishi (RZ06-0290) v2.70 firmware. Try version 1.0.34 or 1.0.66 of the Razer Kishi app.";
                return null;
            }
            var data = File.ReadAllBytes(path);
            if (FirmwareImage.IsOfficialStock(data)) return data;
            error = "That isn't the official Kishi v2.70 image (the checksum doesn't match), so it was not imported.";
            return null;
        }
        catch (InvalidDataException) { error = "That file isn't a valid .apk (it may be an incomplete download)."; return null; }
        catch (Exception ex) { error = "Could not read that file: " + ex.Message; return null; }
    }

    // ------------------------------------------------------------------------------ finding it for the user

    [DllImport("shell32.dll")] private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid id, uint flags, IntPtr token, out IntPtr path);

    private static string? DownloadsFolder()
    {
        try
        {
            if (SHGetKnownFolderPath(new Guid("374DE290-123F-4565-9164-39C4925E467B"), 0, IntPtr.Zero, out var p) == 0)
            {
                var s = Marshal.PtrToStringUni(p);
                Marshal.FreeCoTaskMem(p);
                return s;
            }
        }
        catch { /* fall through */ }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }

    /// <summary>Looks in Downloads, Desktop and Documents (not recursively) for a Razer APK or .bin that really contains the image, newest first.</summary>
    public static List<StockCandidate> FindInUserFolders()
    {
        var found = new List<StockCandidate>();
        var roots = new[]
        {
            DownloadsFolder(),
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
        }.Where(r => !string.IsNullOrEmpty(r) && Directory.Exists(r)).Distinct(StringComparer.OrdinalIgnoreCase);

        foreach (var root in roots)
        {
            IEnumerable<string> files;
            try { files = Directory.EnumerateFiles(root!, "*", SearchOption.TopDirectoryOnly).ToList(); }
            catch { continue; }
            foreach (var f in files)
            {
                try
                {
                    var info = new FileInfo(f);
                    string ext = info.Extension.ToLowerInvariant();
                    string name = info.Name.ToLowerInvariant();
                    bool apk = ext == ".apk" && info.Length is > 3_000_000 and < 150_000_000 && (name.Contains("kishi") || name.Contains("razer") || name.Contains("nexus"));
                    bool bin = ext == ".bin" && info.Length == FirmwareImage.StockLength;
                    if (!apk && !bin) continue;
                    if (Extract(f, out _) is null) continue;
                    found.Add(new StockCandidate(f, info.Name, $"{Path.GetFileName(root)}  ·  {info.Length / 1048576.0:0.#} MB", info.LastWriteTimeUtc));
                }
                catch { /* unreadable or still downloading: skip */ }
            }
        }
        return found.OrderByDescending(c => c.Modified).ToList();
    }
}

public sealed record StockCandidate(string Path, string Name, string Detail, DateTime Modified);

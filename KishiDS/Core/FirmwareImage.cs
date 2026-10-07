using System.Buffers.Binary;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;

namespace KishiDS.Core;

/// <summary>Loads, patches and validates firmware images for the Kishi's application region.</summary>
public static class FirmwareImage
{
    public const uint AppBase = 0x08003000;
    public const uint AppLimit = 0x0800E000;       // diagnostic-log page; the stock settings page is 0x0800F800
    public const uint StackTop = 0x20004000;
    public const string StockSha256 = "AA915363C858E5A06FB1AD15A82D059D50FD22A625D0663A3CE99FCAB24F69BE";

    private static readonly byte[] Marker = BuildMarker();

    private static byte[] BuildMarker()
    {
        var m = new byte[12];
        Encoding.ASCII.GetBytes(ConfigLayout.Magic, m);
        BinaryPrimitives.WriteUInt16LittleEndian(m.AsSpan(8), (ushort)ConfigLayout.Version);
        BinaryPrimitives.WriteUInt16LittleEndian(m.AsSpan(10), (ushort)ConfigLayout.Size);
        return m;
    }

    /// <summary>The firmware image embedded in this program.</summary>
    public static byte[] LoadEmbedded()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("kishi_ds4.bin")
                      ?? throw new InvalidOperationException("embedded firmware missing");
        using var ms = new MemoryStream();
        s.CopyTo(ms);
        return ms.ToArray();
    }

    /// <summary>Offset of the config block, or -1 if the marker is missing or ambiguous.</summary>
    public static int FindBlock(ReadOnlySpan<byte> image)
    {
        int first = image.IndexOf(Marker);
        if (first < 0) return -1;
        if (first + 1 < image.Length && image[(first + 1)..].IndexOf(Marker) >= 0) return -1;
        return first + 256 <= image.Length ? first : -1;
    }

    public static ConfigBlock ReadBlock(ReadOnlySpan<byte> image)
    {
        int at = FindBlock(image);
        if (at < 0) throw new InvalidDataException("This image has no KishiDS config block.");
        return new ConfigBlock(image.Slice(at, ConfigLayout.Size));
    }

    /// <summary>A copy of the image with its config block replaced (CRC recomputed).</summary>
    public static byte[] Patch(byte[] image, ConfigBlock block)
    {
        int at = FindBlock(image);
        if (at < 0) throw new InvalidDataException("This image has no KishiDS config block.");
        var copy = (byte[])image.Clone();
        var b = block.Clone();
        b.RecomputeCrc();
        b.ToBytes().CopyTo(copy, at);
        return copy;
    }

    /// <summary>
    /// Everything that must hold before an image is sent to the bootloader.  Returns an empty list when the
    /// image is safe: vector table inside the application region, sane size, and (for custom images) a valid block.
    /// </summary>
    public static List<string> Validate(ReadOnlySpan<byte> image, bool requireConfigBlock = true)
    {
        var problems = new List<string>();
        if (image.Length < 0xC0 || image.Length > AppLimit - AppBase)
        {
            problems.Add($"Image size {image.Length} bytes is outside the allowed range.");
            return problems;
        }
        uint end = AppBase + (uint)image.Length;
        uint sp = BinaryPrimitives.ReadUInt32LittleEndian(image);
        if (sp != StackTop) problems.Add($"Initial stack pointer {sp:X8} is not {StackTop:X8}.");
        for (int i = 1; i < 48; i++)
        {
            uint v = BinaryPrimitives.ReadUInt32LittleEndian(image[(i * 4)..]);
            if (v == 0) continue;
            if ((v & 1) == 0 || (v & ~1u) < AppBase || (v & ~1u) >= end)
            {
                problems.Add($"Vector {i} ({v:X8}) points outside the image at {AppBase:X8}-{end:X8}; the bootloader would reject it.");
                break;
            }
        }
        if (requireConfigBlock)
        {
            int at = FindBlock(image);
            if (at < 0) problems.Add("No unique config block found.");
            else if (!new ConfigBlock(image.Slice(at, ConfigLayout.Size)).IsValid()) problems.Add("The config block fails its own checksum.");
        }
        return problems;
    }

    public static string Sha256(ReadOnlySpan<byte> data) => Convert.ToHexString(SHA256.HashData(data));

    /// <summary>Size in bytes of Razer's v2.70 application image.</summary>
    public const int StockLength = 28108;

    public static bool IsOfficialStock(ReadOnlySpan<byte> data) =>
        data.Length == StockLength && string.Equals(Sha256(data), StockSha256, StringComparison.OrdinalIgnoreCase);
}

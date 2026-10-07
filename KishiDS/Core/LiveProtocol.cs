namespace KishiDS.Core;

/// <summary>
/// The firmware's live-editing channel on HID feature report 0xAC (see firmware live.h).  Pure packet
/// building and parsing; the device I/O lives in <see cref="DeviceMonitor"/>.
/// </summary>
public static class LiveProtocol
{
    public const byte ReportId = 0xAC, Signature = 0x4B, Protocol = 1;
    public const int ReportSize = 64;      // the largest feature report the device declares (ID + 63)
    public const int ChunkSize = 32, Chunks = ConfigLayout.Size / ChunkSize;

    public enum Cmd : byte { Stage = 1, Apply = 2, ReadSelect = 3, Save = 4, Erase = 5, Reboot = 6 }

    public static byte[] Packet(Cmd cmd, int index = 0, ReadOnlySpan<byte> data = default)
    {
        var p = new byte[ReportSize];
        p[0] = ReportId; p[1] = Signature; p[2] = (byte)cmd; p[3] = (byte)index;
        data.CopyTo(p.AsSpan(4));
        return p;
    }

    /// <summary>Eight STAGE packets followed by APPLY: how a whole config block goes live.</summary>
    public static List<byte[]> PushPackets(ReadOnlySpan<byte> block)
    {
        if (block.Length != ConfigLayout.Size) throw new ArgumentException("config block must be 256 bytes");
        var list = new List<byte[]>();
        for (int i = 0; i < Chunks; i++) list.Add(Packet(Cmd.Stage, i, block.Slice(i * ChunkSize, ChunkSize)));
        list.Add(Packet(Cmd.Apply));
        return list;
    }

    [Flags]
    public enum StatusFlags : byte { None = 0, Unsaved = 1, IdentityDiffers = 2, FromSaved = 4, Pending = 8 }

    public enum Result : byte { Ok = 0, BadBlock = 1, BadCommand = 2, FlashFailed = 3 }

    public readonly record struct Status(Result Result, Cmd LastCmd, uint ActiveCrc, uint PersistedCrc, StatusFlags Flags, int ReadIndex, byte[] Chunk);

    public static Status? ParseStatus(byte[] buf)
    {
        if (buf.Length < 48 || buf[0] != ReportId || buf[1] != Protocol) return null;
        return new Status((Result)buf[2], (Cmd)buf[3], BitConverter.ToUInt32(buf, 4), BitConverter.ToUInt32(buf, 8),
            (StatusFlags)buf[12], buf[13], buf[16..48]);
    }

    public static string Describe(Result r) => r switch
    {
        Result.Ok => "ok",
        Result.BadBlock => "the controller rejected the settings block",
        Result.BadCommand => "the controller didn't understand the command",
        Result.FlashFailed => "the controller couldn't write its flash",
        _ => $"unknown result {(byte)r}",
    };
}

public readonly record struct LiveResult(bool Ok, string Message)
{
    public static LiveResult Success => new(true, "ok");
    public static LiveResult Fail(string why) => new(false, why);
}

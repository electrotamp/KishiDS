using System.Buffers.Binary;
using System.Text;

namespace KishiDS.Core;

/// <summary>
/// The firmware's 256-byte settings block, addressed by the field names in the generated
/// <see cref="ConfigLayout"/>.  All writes are clamped to the schema's legal range, so the
/// block can never hold a value the firmware would reject.
/// </summary>
public sealed class ConfigBlock
{
    private readonly byte[] _data = new byte[ConfigLayout.Size];
    private static readonly Dictionary<string, FieldDef> ByName = ConfigLayout.Fields.ToDictionary(f => f.Name);

    public event Action<string>? Changed;

    public ConfigBlock() => ResetAll();

    public ConfigBlock(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length != ConfigLayout.Size) throw new ArgumentException("config block must be 256 bytes");
        bytes.CopyTo(_data);
        EnforceLocked();
    }

    public byte[] ToBytes() => (byte[])_data.Clone();

    public ConfigBlock Clone() => new(_data);

    /// <summary>Replace every byte (e.g. with the block read back from a controller) and notify once.</summary>
    public void LoadBytes(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length != ConfigLayout.Size) throw new ArgumentException("config block must be 256 bytes");
        bytes.CopyTo(_data);
        EnforceLocked();
        Changed?.Invoke("*");
    }

    public static FieldDef Field(string name) => ByName[name];

    /// <summary>Force locked fields (USB VID/PID/bcdDevice, manufacturer) back to their built-in values, exactly as the firmware's clamp does.</summary>
    private void EnforceLocked()
    {
        foreach (var f in ConfigLayout.Fields.Where(f => f.Locked)) WriteDefault(f);
        RecomputeCrc();
    }

    /// <summary>Load all defaults (and a valid CRC).</summary>
    public void ResetAll()
    {
        foreach (var f in ConfigLayout.Fields) WriteDefault(f);
        RecomputeCrc();
        Changed?.Invoke("*");
    }

    /// <summary>Reset only user-facing settings, keeping the identity strings and calibration unless asked.</summary>
    public void ResetField(string name)
    {
        WriteDefault(ByName[name]);
        RecomputeCrc();
        Changed?.Invoke(name);
    }

    private void WriteDefault(FieldDef f)
    {
        if (f.Name == "magic")
        {
            // The marker fills all 8 bytes (no NUL), unlike the string fields.
            Encoding.ASCII.GetBytes(f.DefaultText, _data.AsSpan(f.Offset, f.Count));
            return;
        }
        if (f.Kind == FieldKind.Char)
        {
            WriteString(f, f.DefaultText);
            return;
        }
        for (int i = 0; i < f.Count; i++) WriteRaw(f, i, f.Default[i]);
    }

    private void WriteRaw(FieldDef f, int index, long value)
    {
        int off = f.Offset + index * (f.ByteSize / f.Count);
        switch (f.Kind)
        {
            case FieldKind.U8: _data[off] = (byte)value; break;
            case FieldKind.U16: BinaryPrimitives.WriteUInt16LittleEndian(_data.AsSpan(off), (ushort)value); break;
            case FieldKind.U32: BinaryPrimitives.WriteUInt32LittleEndian(_data.AsSpan(off), (uint)value); break;
            default: throw new InvalidOperationException(f.Name);
        }
    }

    private long ReadRaw(FieldDef f, int index)
    {
        int off = f.Offset + index * (f.ByteSize / f.Count);
        return f.Kind switch
        {
            FieldKind.U8 => _data[off],
            FieldKind.U16 => BinaryPrimitives.ReadUInt16LittleEndian(_data.AsSpan(off)),
            FieldKind.U32 => BinaryPrimitives.ReadUInt32LittleEndian(_data.AsSpan(off)),
            _ => throw new InvalidOperationException(f.Name),
        };
    }

    public int Get(string name, int index = 0) => (int)ReadRaw(ByName[name], index);

    public void Set(string name, int value, int index = 0)
    {
        var f = ByName[name];
        if (f.Locked) return;
        if (f.Min is int lo && value < lo) value = lo;
        if (f.Max is int hi && value > hi) value = hi;
        if (ReadRaw(f, index) == value) return;
        WriteRaw(f, index, value);
        RecomputeCrc();
        Changed?.Invoke(name);
    }

    public bool GetBit(string name, int bit) => (Get(name) & (1 << bit)) != 0;

    public void SetBit(string name, int bit, bool on) => Set(name, on ? Get(name) | (1 << bit) : Get(name) & ~(1 << bit));

    public string GetString(string name)
    {
        var f = ByName[name];
        var span = _data.AsSpan(f.Offset, f.Count);
        int n = span.IndexOf((byte)0);
        return Encoding.ASCII.GetString(n < 0 ? span : span[..n]);
    }

    /// <summary>Printable ASCII only, truncated to fit with the terminating NUL.</summary>
    public void SetString(string name, string value, bool notify = true)
    {
        var f = ByName[name];
        if (f.Locked) return;
        WriteString(f, value);
        if (notify)
        {
            RecomputeCrc();
            Changed?.Invoke(name);
        }
    }

    private void WriteString(FieldDef f, string value)
    {
        var clean = new string(value.Where(c => c >= 0x20 && c < 0x7F).ToArray());
        if (clean.Length > f.Count - 1) clean = clean[..(f.Count - 1)];
        var span = _data.AsSpan(f.Offset, f.Count);
        span.Clear();
        Encoding.ASCII.GetBytes(clean, span);
    }

    public uint Crc => BinaryPrimitives.ReadUInt32LittleEndian(_data.AsSpan(12));

    public uint ComputeCrc() => Crc32.Compute(_data.AsSpan(ConfigLayout.CrcStart));

    public void RecomputeCrc() => BinaryPrimitives.WriteUInt32LittleEndian(_data.AsSpan(12), ComputeCrc());

    public bool IsValid() =>
        Encoding.ASCII.GetString(_data, 0, 8) == ConfigLayout.Magic &&
        Get("version") == ConfigLayout.Version && Get("size") == ConfigLayout.Size && Crc == ComputeCrc();

    // ---- Calibration helpers -------------------------------------------------------------------

    /// <summary>The settings a user can change (everything except the header), as name -> values, for profile files.</summary>
    public Dictionary<string, object> ToProfile()
    {
        var d = new Dictionary<string, object>();
        foreach (var f in ConfigLayout.Fields)
        {
            if (f.Name is "magic" or "version" or "size" or "crc32" || f.Name.StartsWith("reserved")) continue;
            d[f.Name] = f.Kind == FieldKind.Char ? GetString(f.Name) : f.Count == 1 ? Get(f.Name) : Enumerable.Range(0, f.Count).Select(i => Get(f.Name, i)).ToArray();
        }
        return d;
    }

    public void LoadProfile(IReadOnlyDictionary<string, System.Text.Json.JsonElement> values)
    {
        foreach (var (name, v) in values)
        {
            if (!ByName.TryGetValue(name, out var f) || name is "magic" or "version" or "size" or "crc32" || name.StartsWith("reserved")) continue;
            if (f.Kind == FieldKind.Char) { if (v.ValueKind == System.Text.Json.JsonValueKind.String) SetString(name, v.GetString() ?? ""); }
            else if (f.Count == 1) { if (v.ValueKind == System.Text.Json.JsonValueKind.Number) Set(name, v.GetInt32()); }
            else if (v.ValueKind == System.Text.Json.JsonValueKind.Array)
            {
                int i = 0;
                foreach (var e in v.EnumerateArray()) { if (i < f.Count && e.ValueKind == System.Text.Json.JsonValueKind.Number) Set(name, e.GetInt32(), i); i++; }
            }
        }
        Changed?.Invoke("*");
    }
}

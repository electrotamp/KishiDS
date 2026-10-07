using System.Text;

namespace KishiDS.Core;

/// <summary>`KishiDS.exe --selftest`: checks that the C# side agrees byte-for-byte with the firmware build.</summary>
public static class SelfTest
{
    // dfu-util output captured from real flashing sessions with this controller.
    private const string OkLog = """
        Opening DFU capable USB device...
        DFU state(10) = dfuERROR, status(10) = Device's firmware is corrupt. It cannot return to run-time (non-DFU) operations
        Clearing status
        DFU state(2) = dfuIDLE, status(0) = No error condition is present
        Download done.
        DFU state(6) = dfuMANIFEST-SYNC, status(0) = No error condition is present
        Warning: Invalid DFU suffix signature
        unable to read DFU status after completion (LIBUSB_ERROR_IO)
        """;

    // Same flash, but the controller restarted before dfu-util could read the final status (what this unit usually does).
    private const string AbruptOkLog = """
        DFU state(2) = dfuIDLE, status(0) = No error condition is present
        Copying data from PC to DFU device
        Download done.
        Warning: Invalid DFU suffix signature
        A valid DFU suffix will be required in a future dfu-util release
        unable to read DFU status after completion (LIBUSB_ERROR_PIPE)
        """;

    private const string RejectLog = """
        Download done.
        DFU state(10) = dfuERROR, status(7) = Programmed memory failed verification
        """;

    public static int Run(TextWriter o)
    {
        try { return RunChecks(o); }
        catch (Exception ex) { o.WriteLine("EXCEPTION: " + ex); return 2; }
    }

    private static int RunChecks(TextWriter o)
    {
        int failures = 0;
        void Check(string name, bool ok) { o.WriteLine($"{(ok ? "PASS" : "FAIL")}  {name}"); if (!ok) failures++; }

        Check("CRC-32 check vector", Crc32.Compute(Encoding.ASCII.GetBytes("123456789")) == 0xCBF43926u);

        var image = FirmwareImage.LoadEmbedded();
        Check("embedded image validates", FirmwareImage.Validate(image).Count == 0);

        var fromImage = FirmwareImage.ReadBlock(image);
        var defaults = new ConfigBlock();
        Check("embedded image's block is valid", fromImage.IsValid());
        Check("C# defaults == block stamped by the firmware build (layout + CRC identical)", defaults.ToBytes().SequenceEqual(fromImage.ToBytes()));
        Check("patching defaults leaves the image unchanged", FirmwareImage.Patch(image, defaults).SequenceEqual(image));

        var edited = new ConfigBlock();
        edited.Set("left_dead", 21);
        edited.Set("button_map", 7, 0);
        edited.SetString("product", "Test Pad");
        edited.Set("vid", 0x1234);            // locked: must be ignored
        edited.SetString("manufacturer", "Evil");
        var patched = FirmwareImage.Patch(image, edited);
        Check("patched image differs in the block only",
            patched.Length == image.Length && Enumerable.Range(0, image.Length).Count(i => patched[i] != image[i]) <= ConfigLayout.Size);
        var back = FirmwareImage.ReadBlock(patched);
        Check("patched block round-trips", back.IsValid() && back.Get("left_dead") == 21 && back.Get("button_map", 0) == 7 && back.GetString("product") == "Test Pad");
        Check("locked USB identity ignores edits (VID/PID DS4, manufacturer ElectroTamp KishiDS)",
              back.Get("vid") == 0x054C && back.Get("pid") == 0x05C4 && back.GetString("manufacturer") == "ElectroTamp KishiDS");
        Check("patched image still validates", FirmwareImage.Validate(patched).Count == 0);

        edited.Set("left_dead", 999);
        Check("writes are clamped to the legal range", edited.Get("left_dead") == 50);
        edited.SetString("product", new string('x', 100));
        Check("strings are truncated to fit with a NUL", edited.GetString("product").Length == 31);
        edited.SetString("serial", "Spoofed");   // locked: the firmware uses the factory serial
        Check("locked serial ignores edits", edited.GetString("serial") == "");
        Check("serial is read from a stock controller's instance ID",
              DeviceMonitor.SerialFromInstanceId(@"USB\VID_27F8&PID_0BBF\SAMPLE000000001", 0x27F8, 0x0BBF) == "SAMPLE000000001");
        Check("a generated instance ID (no serial) and other devices give no serial",
              DeviceMonitor.SerialFromInstanceId(@"USB\VID_27F8&PID_0BBF\6&1a2b3c4d&0&2", 0x27F8, 0x0BBF) is null &&
              DeviceMonitor.SerialFromInstanceId(@"USB\VID_27F8&PID_0BC0\12345678", 0x27F8, 0x0BBF) is null);

        var bad = (byte[])image.Clone();
        bad[4] = 0x00; bad[5] = 0x00; bad[6] = 0x00; bad[7] = 0x00;   // reset vector 0
        bad[8] = 0xCD; bad[9] = 0xA0; bad[10] = 0x00; bad[11] = 0x08; // an out-of-region vector (what the old stage1 had)
        Check("an out-of-region vector table is rejected", FirmwareImage.Validate(bad).Count > 0);
        Check("an image without a block is rejected", FirmwareImage.Validate(new byte[1000]).Count > 0);

        Check("flash log: normal success is recognised (even with the sticky status-10 at the start)", DfuService.Interpret(OkLog).Success);
        Check("flash log: abrupt restart after the download (status unreadable) counts as success", DfuService.Interpret(AbruptOkLog).Success);
        Check("flash log: status 7 verification failure is reported", !DfuService.Interpret(RejectLog).Success && DfuService.Interpret(RejectLog).Message.Contains("rejected"));
        Check("flash log: no device is reported", !DfuService.Interpret("dfu-util: No DFU capable USB device available").Success);
        Check("flash log: an incomplete transfer is not a success", !DfuService.Interpret("Copying data from PC to DFU device").Success);

        // ---- live editing protocol ----
        var live = new ConfigBlock();
        live.Set("led_mode", 2);
        live.Set("button_map", 5, 3);
        var pk = LiveProtocol.PushPackets(live.ToBytes());
        Check("live: push is 8 stage packets + apply, 64 bytes each", pk.Count == 9 && pk.All(p => p.Length == 64 && p[0] == 0xAC && p[1] == 0x4B));
        Check("live: stage packets reassemble to the block", Enumerable.Range(0, 8).SelectMany(i => pk[i][4..36]).SequenceEqual(live.ToBytes()) &&
                                                             Enumerable.Range(0, 8).All(i => pk[i][2] == 1 && pk[i][3] == i) && pk[8][2] == 2);

        var oldTele = new byte[58]; oldTele[0] = 0xAB; oldTele[1] = 1; oldTele[2] = 3; oldTele[17] = 0x78; oldTele[18] = 0x56; oldTele[19] = 0x34; oldTele[20] = 0x12;
        var t0 = DeviceMonitor.ParseTelemetry(oldTele);
        Check("telemetry: old firmware parses and is not live", t0 is { Live: false, ConfigCrc: 0x12345678, Configured: true, ConfigFromImage: true });
        var newTele = (byte[])oldTele.Clone(); newTele[2] = 1 | 0x04 | 0x08 | 0x10 | 0x20; newTele[21] = 0xEF; newTele[22] = 0xBE; newTele[23] = 0xAD; newTele[24] = 0xDE; newTele[25] = 1;
        var t1 = DeviceMonitor.ParseTelemetry(newTele);
        Check("telemetry: live firmware flags and persisted CRC", t1 is { Live: true, FromSaved: true, Unsaved: true, IdentityDiffers: true, PersistedCrc: 0xDEADBEEF });
        var noProto = (byte[])newTele.Clone(); noProto[25] = 0;
        Check("telemetry: live bit without protocol byte is not trusted", DeviceMonitor.ParseTelemetry(noProto) is { Live: false });

        var st = new byte[58]; st[0] = 0xAC; st[1] = 1; st[2] = 1; st[3] = 2; st[4] = 1; st[8] = 2; st[12] = 5; st[13] = 7; for (int i = 0; i < 32; i++) st[16 + i] = (byte)i;
        var ps = LiveProtocol.ParseStatus(st);
        Check("live status parses", ps is { Result: LiveProtocol.Result.BadBlock, LastCmd: LiveProtocol.Cmd.Apply, ActiveCrc: 1, PersistedCrc: 2, ReadIndex: 7 } &&
                                    ps.Value.Flags == (LiveProtocol.StatusFlags.Unsaved | LiveProtocol.StatusFlags.FromSaved) && ps.Value.Chunk[31] == 31);
        Check("live status rejects other reports", LiveProtocol.ParseStatus(new byte[58]) is null);

        var viaBytes = new ConfigBlock();
        viaBytes.LoadBytes(live.ToBytes());
        Check("config can be loaded from a block read off the device", viaBytes.IsValid() && viaBytes.Get("led_mode") == 2 && viaBytes.Get("button_map", 3) == 5);

        o.WriteLine(failures == 0 ? "ALL PASSED" : $"{failures} FAILED");
        return failures == 0 ? 0 : 1;
    }
}

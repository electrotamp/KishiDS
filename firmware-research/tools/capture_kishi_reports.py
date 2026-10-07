"""Read and print distinct Kishi HID input reports without sending data to it."""

import argparse
import hid
import time


parser = argparse.ArgumentParser()
parser.add_argument("label")
parser.add_argument("--seconds", type=float, default=5.0)
args = parser.parse_args()

devices = hid.enumerate(0x27F8, 0x0BBF)
if not devices:
    raise SystemExit("Razer Kishi (27F8:0BBF) is not connected.")

device = hid.device()
device.open_path(devices[0]["path"])
device.set_nonblocking(1)
reports = {}
end = time.monotonic() + args.seconds

try:
    while time.monotonic() < end:
        report = device.read(64)
        if report:
            encoded = bytes(report).hex(" ")
            reports[encoded] = reports.get(encoded, 0) + 1
        time.sleep(0.002)
finally:
    device.close()

print(f"{args.label}: {len(reports)} distinct report(s)")
for report, count in sorted(reports.items()):
    print(f"{report}  count={count}")

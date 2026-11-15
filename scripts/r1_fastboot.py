#!/usr/bin/env python3
"""Request FASTBOOT from this R1's MediaTek preloader; no firmware writes.

Protocol reference: RabbitHoleEscapeR1/r1_escape/mtkbootcmd.py.
Do not run while another program is using the preloader.
"""
import argparse
import time
import serial
from serial.tools import list_ports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=float, default=180)
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    print('Waiting for R1 preloader 0e8d:2000; connect the powered-off device.', flush=True)
    while time.monotonic() < deadline:
        ports = [p for p in list_ports.comports() if (p.vid, p.pid) == (0x0E8D, 0x2000)]
        if len(ports) > 1:
            raise SystemExit('More than one MediaTek preloader found; refusing ambiguous target.')
        for port in ports:
            try:
                with serial.Serial(port.device, baudrate=115200, timeout=0.5, write_timeout=1) as dev:
                    ready = dev.read(5)
                    if ready == b'READY':
                        dev.write(b'FASTBOOT')
                        dev.flush()
                        print('FASTBOOT request sent. Verify with fastboot devices/getvar.', flush=True)
                        return
            except (serial.SerialException, OSError) as exc:
                print(type(exc).__name__ + '; retrying preloader connection.', flush=True)
        time.sleep(0.05)
    raise SystemExit('No READY handshake before timeout; nothing flashed.')


if __name__ == '__main__':
    main()

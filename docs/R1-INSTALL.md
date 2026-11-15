# Manual r1 installation

This is the recorded route for **one physical r1**, not a universal firmware installer. Checked on 2026-11-15: rabbitOS `2.3.1_20260910192308` → LineageOS 21 / Android 14, retaining Rabbit's kernel and vendor firmware. App, managed Wi-Fi, QR pairing and a VM reply work; complete hardware input, camera rotation, battery use and stock restoration are still being checked.

**Unlocking wipes user data.** Rabbit requires a permanent warranty waiver for Developer Mode. Back up your device first. Keep the bootloader unlocked while custom images are installed. [Rabbit's official instructions](https://www.rabbit.tech/support/article/unlock-bootloader-rabbit-r1).

## 1. Gather files and back up

Use a USB data cable, `adb`/`fastboot`, Python 3.12 and at least 20 GB free on your setup computer. Our host was macOS; Windows-specific driver/setup steps have not been tested here. Keep all images, backups and device identifiers outside Git, e.g. in this repository's ignored `private/r1/`.

| Component | Version used |
| --- | --- |
| Android platform tools | 37.0.1 |
| [Lineage GSI](https://sourceforge.net/projects/andyyan-gsi/files/lineage-21-pre-qpr2-td/) | `lineage-21.0-20261109-UNOFFICIAL-arm64_bvN.img.gz` |
| [Magisk](https://github.com/topjohnwu/Magisk/releases/tag/v31.1) | 31.1 |
| [Rabbit firmware archive](https://github.com/rabbit-hmi-oss/firmware/releases/tag/v0.8.293) | v0.8.293, **only its empty `userdata.img`** for this route |

The GSI's compressed SHA-256 is `7e56f0ea7a58e7bda51801b36d946e7751daf2203a4536e263f277f70781c75e`. Decompress it to `private/r1/system.img`; the resulting SHA-256 is `7929f7760b072238e6a05506377e5936d635118fa5992e5ee306afc2f7f7fd77`. Check both before flashing. This exact image was tested, not any file advertised as “latest”.

Back up the original GPT and **every non-userdata partition**, including `super`, both `boot` slots, all six `vbmeta*` images, NVRAM/calibration and metadata. We used [mtkclient](https://github.com/bkerler/mtkclient) read commands at commit `118a000a83ea31c4f427fe5225abfe964546e05e`, with Python 3.12 and `--serialport`. Its bulk read equivalent, from a separately prepared mtkclient environment, is:

```sh
python mtk.py rl /absolute/path/to/private/r1/backup --skip userdata --serialport
```

Start the reader **before** connecting the fully powered-off r1. Use one USB tool and one r1 at a time. Compare each saved partition size with the device's GPT and record SHA-256 hashes; do not proceed with missing/failed reads. `super` alone took about 32 minutes on our Mac. This excludes personal user data; export anything needed from rabbitOS separately. Preserve your own current images: the older published Rabbit archive is not a backup of your device.

## 2. Enable unlock and enter fastboot

In **OS3 → Settings → r1**, enable Developer Mode with the r1's IMEI (shown in its device information). With the r1 **on and online**, apply **Device modification → Unlock** or the **bootloader unlock** toggle. This grants permission; it does not itself unlock the bootloader. [Official procedure](https://www.rabbit.tech/support/article/unlock-bootloader-rabbit-r1).

Stop the backup reader. Use Rabbit's [Enter Fastboot Mode tool](https://rabbit-hmi-oss.github.io/flashing/), or our terminal helper from the Unrabbit repository:

```sh
python3 -m venv private/r1-tools
private/r1-tools/bin/pip install pyserial==3.5
private/r1-tools/bin/python scripts/r1_fastboot.py
```

Then connect the powered-off r1. If necessary, hold its side button about ten seconds to restart while the helper is waiting. The helper only sends the preloader's FASTBOOT request; it does not flash or unlock anything.

```sh
fastboot devices
R1_SERIAL='YOUR_R1_FASTBOOT_SERIAL'
fastboot -s "$R1_SERIAL" flashing get_unlock_ability
fastboot -s "$R1_SERIAL" getvar current-slot
```

Unlock permission must be **nonzero**; our device reported `16777216`, not `1`. If it is `0`, boot rabbitOS, reapply the portal toggle, leave it online for a minute and retry. Record the active slot. The following example uses `a`, the slot tested here; set `R1_SLOT` to your reported `a` or `b`.

```sh
R1_SLOT='a'
fastboot -s "$R1_SERIAL" flashing unlock
fastboot -s "$R1_SERIAL" getvar unlocked
```

Continue only with `unlocked: yes` and verified backups. Check each subsequent command for `OKAY` before continuing.

## 3. Flash Android while retaining Rabbit's hardware firmware

Prepare copies of your six original AVB metadata images. This sets the verification flags while preserving the rest of each image; it never edits the backups:

```sh
python3 - <<'PY'
from pathlib import Path
root = Path('private/r1')
out = root / 'noverify'
out.mkdir(parents=True, exist_ok=True)
for slot in ('a', 'b'):
    for base in ('vbmeta', 'vbmeta_system', 'vbmeta_vendor'):
        name = f'{base}_{slot}.bin'
        data = bytearray((root / 'backup' / name).read_bytes())
        assert len(data) >= 256 and data[:4] == b'AVB0', name
        flags = int.from_bytes(data[120:124], 'big') | 3
        data[120:124] = flags.to_bytes(4, 'big')
        (out / name).write_bytes(data)
PY
```

In **bootloader fastboot**, flash those copies and initialize empty user data. Copy the archive's empty image to `private/r1/userdata.img` first.

```sh
for part in vbmeta_a vbmeta_system_a vbmeta_vendor_a vbmeta_b vbmeta_system_b vbmeta_vendor_b; do
  fastboot -s "$R1_SERIAL" flash "$part" "private/r1/noverify/$part.bin" || exit 1
done
fastboot -s "$R1_SERIAL" erase userdata
fastboot -s "$R1_SERIAL" flash userdata private/r1/userdata.img
fastboot -s "$R1_SERIAL" erase metadata
fastboot -s "$R1_SERIAL" reboot fastboot
```

In **fastbootd**, check `is-userspace: yes`, the same active slot and no pending snapshot update. The tested device had logical `system_a`, `product_a` and `vendor_a`. Stop if the layout/state differs; this is not a recipe for arbitrary Android firmware.

```sh
fastboot -s "$R1_SERIAL" getvar is-userspace
fastboot -s "$R1_SERIAL" getvar current-slot
fastboot -s "$R1_SERIAL" getvar snapshot-update-status
fastboot -s "$R1_SERIAL" getvar "is-logical:system_$R1_SLOT"
fastboot -s "$R1_SERIAL" getvar "is-logical:product_$R1_SLOT"
fastboot -s "$R1_SERIAL" delete-logical-partition "product_$R1_SLOT"
fastboot -s "$R1_SERIAL" -S 100M flash "system_$R1_SLOT" private/r1/system.img
fastboot -s "$R1_SERIAL" reboot
```

The original vendor, modem, preloader, partition table and calibration remain in place. Only the selected system/product slot is replaced; the other slot is not configured as a second working Android installation.

## 4. Boot and enable the hardware integration

If you only see a charging battery, hold the side button about five seconds until the boot logo. Finish Android setup, enable Developer options / USB debugging and allow your computer. Use Wi-Fi; removing an unused locked SIM avoids getting stuck at Android's PIN/PUK screen.

Patch **your own original `boot_<active-slot>` image** with Magisk 31.1. Never use another device's patched boot image. The [official Magisk instructions](https://topjohnwu.github.io/Magisk/install.html) explain image patching; the manual variant we used is below, with `KEEPVERITY=true` / `KEEPFORCEENCRYPT=true`.

<details>
<summary>Manual Magisk patch used for this GSI</summary>

Save the official APK as `private/r1/Magisk-v31.1.apk`; its SHA-256 is `89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7`. Extract its own scripts and ARM64 binaries:

```sh
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
out = Path('private/r1/magisk-bin')
out.mkdir(parents=True, exist_ok=True)
with ZipFile('private/r1/Magisk-v31.1.apk') as apk:
    for name in apk.namelist():
        leaf = Path(name).name
        if name.startswith('assets/') and leaf.endswith('.sh'):
            (out / leaf).write_bytes(apk.read(name))
        elif name.startswith('lib/arm64-v8a/') and leaf.endswith('.so'):
            (out / leaf.removeprefix('lib').removesuffix('.so')).write_bytes(apk.read(name))
PY
adb devices
R1_SERIAL='YOUR_R1_ADB_SERIAL'
adb -s "$R1_SERIAL" root
adb -s "$R1_SERIAL" wait-for-device
adb -s "$R1_SERIAL" install -r private/r1/Magisk-v31.1.apk
adb -s "$R1_SERIAL" push private/r1/magisk-bin /data/local/tmp/unrabbit-magisk
adb -s "$R1_SERIAL" push "private/r1/backup/boot_$R1_SLOT.bin" /data/local/tmp/unrabbit-magisk/boot.img
adb -s "$R1_SERIAL" shell 'cd /data/local/tmp/unrabbit-magisk && KEEPVERITY=true KEEPFORCEENCRYPT=true sh boot_patch.sh boot.img'
adb -s "$R1_SERIAL" pull /data/local/tmp/unrabbit-magisk/new-boot.img private/r1/magisk-boot.img
```

Unpack both images in separate directories and compare the two `kernel` hashes. Our hashes were identical; stop if yours differ.

```sh
adb -s "$R1_SERIAL" shell 'cd /data/local/tmp/unrabbit-magisk && mkdir -p verify-original verify-patched'
adb -s "$R1_SERIAL" shell 'cd /data/local/tmp/unrabbit-magisk/verify-original && ../magiskboot unpack ../boot.img'
adb -s "$R1_SERIAL" shell 'cd /data/local/tmp/unrabbit-magisk/verify-patched && ../magiskboot unpack ../new-boot.img'
adb -s "$R1_SERIAL" shell sha256sum /data/local/tmp/unrabbit-magisk/verify-original/kernel /data/local/tmp/unrabbit-magisk/verify-patched/kernel
```

Prepare Magisk's runtime, as in our manual installation:

```sh
adb -s "$R1_SERIAL" shell mkdir -p /data/adb/magisk
adb -s "$R1_SERIAL" push private/r1/magisk-bin/. /data/adb/magisk/
adb -s "$R1_SERIAL" shell chown -R 0:0 /data/adb/magisk
adb -s "$R1_SERIAL" shell chmod -R 755 /data/adb/magisk
adb -s "$R1_SERIAL" shell chmod 700 /data/adb
adb -s "$R1_SERIAL" reboot bootloader
```

Re-read the fastboot serial and active slot; use those values to flash **only** the selected boot partition:

```sh
fastboot devices
R1_SERIAL='YOUR_R1_FASTBOOT_SERIAL'
fastboot -s "$R1_SERIAL" getvar current-slot
fastboot -s "$R1_SERIAL" flash "boot_$R1_SLOT" private/r1/magisk-boot.img
fastboot -s "$R1_SERIAL" reboot
```

Boot Android fully again, then check Magisk's status before installing the module. Keep the original boot backup untouched.

</details>

The small [hardware module](../hardware/r1/unrabbit_r1_hardware/) contains only the F8/WAKE side-button mapping, `ro.vendor.cua.enable=0` for stable USB and the device-admin feature declaration. The following is the manual module installation used with this debug-capable GSI and working Magisk. Use the **ADB serial** now, which may differ from fastboot:

```sh
adb devices
R1_SERIAL='YOUR_R1_ADB_SERIAL'
adb -s "$R1_SERIAL" root
adb -s "$R1_SERIAL" wait-for-device
adb -s "$R1_SERIAL" push hardware/r1/unrabbit_r1_hardware /data/adb/modules/
adb -s "$R1_SERIAL" shell chown -R 0:0 /data/adb/modules/unrabbit_r1_hardware
adb -s "$R1_SERIAL" shell 'find /data/adb/modules/unrabbit_r1_hardware -type d -exec chmod 755 {} +'
adb -s "$R1_SERIAL" shell 'find /data/adb/modules/unrabbit_r1_hardware -type f -exec chmod 644 {} +'
adb -s "$R1_SERIAL" shell chcon -R u:object_r:system_file:s0 /data/adb/modules/unrabbit_r1_hardware/system
adb -s "$R1_SERIAL" reboot
```

After boot, confirm Magisk is active, the keylayout contains `key 116 F8 WAKE`, and `adb shell pm list features` includes `android.software.device_admin`. The stock wheel mapping is retained. Unrabbit itself needs no root grant. Return to [Install Unrabbit](../SETUP.md#2-install-unrabbit-over-usb).

## Common snags

| Symptom | What worked / what to check |
| --- | --- |
| USB is visible, but no ADB | Stock rabbitOS USB detection is not Android debugging. Use preloader/fastboot for flashing; enable debugging after Android boots. |
| Backup/fastboot tool sees nothing | Start one listener first, then connect the powered-off r1; try a ten-second side-button restart while it waits. |
| Unlock permission stays zero | Reapply OS3 Unlock while rabbitOS is online, wait one minute, then re-enter fastboot. |
| Black/charging screen | A short press reveals charging state; hold about five seconds for a full boot. |
| Android setup or a preinstalled app loops | Our build needed the unused Lineage setup wizard and Eleven music app disabled after setup. Do not treat every crash as a failed flash. |
| `/data` is only about 3 GB | The old empty image can leave a small ext4 filesystem. We expanded it with root `resize2fs` on the **actual block device mounted at `/data`**. Identify that mapping first; never copy a `/dev/block/dm-N` number from another device. |
| VM QR is rejected | Use the current wizard QR; screenshots expire after ten minutes. Wi-Fi connectivity does not prove VM reachability. |

## Returning to rabbitOS

Rabbit documents stock flashing through its [official recovery tool](https://rabbit-hmi-oss.github.io/flashing/) and [firmware releases](https://github.com/rabbit-hmi-oss/firmware/releases). **We have not yet tested restoring or relocking this installation.** A factory reset only clears Android data. Keep your verified original backups, and never relock the bootloader with custom images installed.

The stock-kernel/GSI approach was informed by [herdr-os's installation notes](https://github.com/herdr-os/core/blob/main/docs/INSTALL.md). The commands and module above describe Unrabbit's own tested device, without that project's app or backend.

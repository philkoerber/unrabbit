# Keep the VM service running

Run the terminal wizard and pair first. Finish/cancel outstanding requests, then stop the foreground process with Ctrl+C. Never run two services against the same state directory.

The example uses the account and paths from [Setup](../SETUP.md). Adapt **all** user, group, checkout and config paths to your installation. This follows the systemd pattern running on our development VM; automatic service installation is not part of the wizard.

As the VM administrator, save `/etc/systemd/system/unrabbit.service`:

```ini
[Unit]
Description=Unrabbit VM service
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=unrabbit
Group=unrabbit
WorkingDirectory=/home/unrabbit/unrabbit
ExecStart=/home/unrabbit/unrabbit/.venv-setup/bin/python /home/unrabbit/unrabbit/scripts/unrabbit.py run --config /home/unrabbit/.config/unrabbit/vm.json
Environment=PATH=/home/unrabbit/.local/bin:/usr/local/bin:/usr/bin:/bin
Restart=on-failure
RestartSec=3
TimeoutStopSec=30
UMask=0077

[Install]
WantedBy=multi-user.target
```

If Codex/Node lives elsewhere, add its runtime directory to `PATH`. The service uses the account's existing login; do not copy credentials from your computer. If setup used custom XDG directories, point `--config` at that actual config and preserve the same runtime environment. The saved config already contains the state/workspace paths.

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now unrabbit.service
sudo systemctl status unrabbit.service --no-pager
sudo journalctl -u unrabbit.service -n 30 --no-pager
```

Confirm the r1's VM icon reconnects and a request completes. Logs and files under the account's config/state directories stay private.

To pair another device, first finish outstanding work, then stop this service, run `setup` interactively as the service account and scan its new QR. Stop the wizard and start the service again. Existing paired identities survive; deleting `devices.json` would revoke them and is unnecessary for normal pairing/restart.

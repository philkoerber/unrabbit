# Local VM development

Run the Android emulator and an Ubuntu 24.04 container on the same development computer. No remote VM, Rabbit account, model credentials or cloud calls are needed for the default demo agent. The eventual device still needs only its own VM.

## Start both sides

Install Docker with Compose, Python 3.9+ and the Android tools described in the [development guide](../docs/DEVELOPMENT.md). Build/install the debug APK and open **Demo öffnen** once. From the repository root:

```sh
python3 scripts/local_vm.py up
python3 scripts/local_vm.py attach
```

The helper uses `emulator-5572` by default; pass `--serial` for another emulator. Enable the emulator's host microphone, allow microphone access and select English or German in Unrabbit. Hold **←**, speak after listening starts, then release. The app sends compressed audio to Ubuntu for transcription; the reply also comes from Ubuntu. Photos remain local drafts until that input completes.

The Docker build installs `requirements-speech.txt` and downloads the pinned multilingual Whisper `base` model as the unprivileged runtime user. This needs network access and additional build time/disk space. The resulting image keeps the model under `/opt/unrabbit-model`; recognition makes no hosted speech call. For a normal Ubuntu installation, follow [speech setup](../SETUP.md#3-prepare-your-ubuntu-vm). `UNRABBIT_SPEECH_MODEL` can select an existing local model directory; otherwise setup uses `$XDG_DATA_HOME/unrabbit/speech/base` (or `~/.local/share/unrabbit/speech/base`). Run setup and the service with the same environment.

`up` builds the container and waits for readiness. `attach` verifies that the target is an emulator, adds an ADB reverse connection and privately installs a generated development credential using Android's `run-as`. It restarts the app without clearing its data. A verified QR pairing selects the real SSH transport and takes precedence over this developer transport.

```sh
python3 scripts/local_vm.py status
python3 scripts/check_local_vm.py
```

The guided recovery test waits up to two minutes for you to speak one request, includes any waiting photo drafts, then stops/restarts the app and verifies result recovery, retained history and exact uploaded photo bytes. It refuses a non-demo agent and saves its report under ignored `private/local-vm-checks/`. For slower emulators, start with `UNRABBIT_DEMO_DELAY=5 python3 scripts/local_vm.py up`.

To return to the standalone app demo and stop the container:

```sh
python3 scripts/local_vm.py detach
python3 scripts/local_vm.py down
```

Finish pending requests before detaching. The helper refuses `attach`/`detach` while the app has a QR-paired VM, because that pairing takes precedence over Docker; it never silently replaces or removes it. `down` preserves the Docker volume `unrabbit-local_vm-data`, including the database, photos and development credential. Do not use `docker compose down -v` unless intentionally deleting that development state. A different host port can be selected with `--port` or `UNRABBIT_DEV_PORT`; use the same value for attach/detach.

## Real Codex locally

Opt in from the repository root:

```sh
python3 scripts/local_vm.py login
python3 scripts/local_vm.py up --agent codex
python3 scripts/local_vm.py attach
```

`login` builds the optional Ubuntu image with Codex CLI **0.165.0**, then runs the [official device-login flow](https://learn.chatgpt.com/docs/auth#login-on-headless-devices). Complete sign-in in your own browser. Device-code login may need enabling in your ChatGPT account/workspace settings. No host login cache is copied, no password/API key belongs in chat, and the helper does not save a login transcript.

The VM's Codex login stays in `unrabbit-local_codex-home`; its workspace stays in `unrabbit-local_codex-workspace`. These named Docker volumes are outside Git and are not part of the image. Local reports stay under ignored `private/`. The reusable image/Compose recipe and offline tests can be versioned. Real Codex prompts and current attachments are sent to OpenAI; this is local development, not an offline model.

To use the same installed/authenticated Codex in the terminal wizard:

```sh
python3 scripts/local_vm.py wizard --agent codex
```

The wizard always includes the VM address, SSH port and QR steps. It does not advertise an internal Docker IP as a device endpoint; inside Docker, enter the address reachable from the emulator manually and publish the selected SSH port. On a VM it suggests the SSH session's server address or a local routed address, retaining saved overrides. Its own SSH port defaults to 2222. The emulator helper uses its existing ADB transport and a fixed private container workspace, independently of that settings draft. For an emulator-only QR test, publish the application port explicitly, for example `docker compose --profile setup run --build --rm -p 127.0.0.1:2222:2222 setup`, and use `10.0.2.2` as the address seen by the Android emulator. Real VM setup uses its own reachable address. The optional image installs Codex only; Claude Code’s adapter is still pending.

Both modes use the existing VM database and server identity, so chat data survives switching. Finish or cancel requests first; `up` refuses to interrupt outstanding server jobs. Switching to Codex makes subsequent requests use the live agent, with the recent server conversation as context. The CLI/login check runs before replacing the current service. To return to offline demo responses:

```sh
python3 scripts/local_vm.py up --agent demo
```

`down` stops the service and retains the volumes. To sign out explicitly:

```sh
docker compose -f compose.yaml -f compose.codex.yaml run --rm --no-deps --entrypoint codex setup logout
```

The optional Compose file keeps UID 10001, dropped capabilities, no-new-privileges, a read-only container root and a loopback-only host port. It relaxes Docker's outer seccomp filter (`seccomp=unconfined`) so Codex's bubblewrap sandbox can create unprivileged namespaces. **Codex still runs with its read-only sandbox and no automatic escalation.** Allowed reads and blocked workspace writes were checked directly on Docker Desktop. The host filesystem and Docker socket are not mounted. Other Docker hosts may impose additional namespace/AppArmor restrictions; see [OpenAI's sandbox documentation](https://learn.chatgpt.com/docs/agent-approvals-security).

## What is implemented

- One durable SQLite stream of jobs and original messages; photo bytes are stored transactionally with each request.
- Stable UUIDs: retrying the same payload returns the existing job. Reusing its ID for different content returns a conflict.
- A single worker processes requests in order. The device can disconnect or sleep; completed answers remain available.
- Cancellation is terminal, including a tombstone for a cancellation arriving before its submission. A late worker result cannot overwrite it.
- On a service restart, queued work continues; an already-running job becomes `interrupted` and is **not** automatically repeated. This does not promise exactly-once external side effects.
- Context comes from recent completed server turns, capped at 20 turns / 24,000 text characters. Original records remain intact. Semantic summarization, search and per-project/MCP routing are not implemented yet.
- The app persists its outbox and backend identity, recovers results after restart and backs off on network errors. Job polling runs only while active and work is outstanding; the foreground VM icon also checks authenticated health every 30 seconds. Old standalone demo history is not uploaded.

The default demo container runs as UID 10001 without capabilities, with a read-only root filesystem. Only its data volume and temporary directory are writable. The host port is bound to `127.0.0.1`. HTTP requires the generated bearer credential and server identity. The HTTP development credential is never placed in a QR, command argument or repository file. The SSH wizard’s QR carries a separate single-use enrollment code.

This HTTP server is a **development/internal boundary**, not an Internet-facing production API. Android’s loopback HTTP implementation exists only in the debug build. Both builds support pinned SSH pairing through a separate application endpoint. Unattended service installation remains future work.

## Agent boundary

The [terminal wizard](../SETUP.md#terminal-wizard) configures this same service: `scripts/unrabbit.py setup`, then `scripts/unrabbit.py run`, using the setup venv. It discovers installed CLIs, checks the supported Codex CLI/login, stores a private configuration, starts the restricted SSH service and displays a ten-minute enrollment QR. It never executes a model prompt during setup. The selected executable, workspace and state directory are read by the service on startup; no manual copy of settings is needed. The app generates its key locally, pins the server identity from the QR and saves the pairing only after a successful key-authenticated health check. No system SSH keys or authorized_keys files are used.

Only Codex and the demo agent are currently selectable. Detected Claude Code, Gemini CLI and OpenCode installations are identified as lacking an adapter. No generic shell-command adapter is exposed. Persistent Codex threads and MCP integration remain future work.

The default `DemoAgent` is deterministic and offline. It confirms the real transport and retains recent context; it does not claim to understand pictures or execute tools.

An optional `CodexAgent` adapter invokes a separately installed, authenticated Codex CLI with a server-chosen workspace. Its named permission profile permits command reads only in that workspace, the current job's temporary files and Codex's minimal runtime paths. The service state, SSH directory and Codex home are explicitly denied; commands cannot write or use the network. Model/authentication traffic remains separate. It sends current images and bounded conversation context through stdin/files, enforces a timeout and terminates the process group on cancellation. It uses `--ignore-user-config`, `--ephemeral` and `--strict-config`; personal MCP configuration is not inherited and unsupported settings fail rather than silently broadening access. See [OpenAI's permission profiles](https://learn.chatgpt.com/docs/permissions) and [non-interactive Codex documentation](https://learn.chatgpt.com/docs/non-interactive-mode).

The adapter's CLI contract, timeout and cancellation are tested using a fixture executable. **Real text and native-camera image replies have also been verified through remote SSH**, including recovery after app restart with an image request pending. SIGTERM cleanly stops a running agent and preserves its job as interrupted. Model-driven tool actions and approval behavior still need their own end-to-end checks. The default demo image contains no Codex binary or credentials; the optional image above adds the CLI and a separate login. Using a hosted Codex model is not offline, even when the service runs locally.

For a manually prepared Linux account without root/sudo, the same service can run directly from a checkout:

```sh
cd vm
python3 -m unrabbit_vm --state /path/to/private-state --workspace /path/to/workspace
# After separately installing/authenticating a compatible Codex CLI under that account:
python3 -m unrabbit_vm --state /path/to/private-state --workspace /path/to/workspace --agent codex
```

Run one command at a time. The direct HTTP development entry point defaults to loopback and refuses root. The wizard starts the separate SSH endpoint on its configured port. The tested CLI interface is Codex 0.165.0; actual provider access and project/tool permissions must be configured on the eventual VM. No remote installation has been performed by the local development helper.

## Tests

```sh
python3 -m venv .venv-setup
.venv-setup/bin/pip install -r scripts/requirements-setup.txt
PYTHONPATH=vm .venv-setup/bin/python -m unittest discover -s vm/tests -v
python3 -m unittest discover -s scripts/tests -v
cd android
UNRABBIT_TEST_PYTHON="$PWD/../.venv-setup/bin/python" ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

The HTTP development service uses Python’s standard library. The SSH service and wizard additionally use AsyncSSH and the pinned QR dependency. Tests exercise HTTP authentication, persistence, retry conflicts, cancellation races, restart recovery, bounded context, image integrity and the agent boundary, plus setup validation, CLI detection, cancellation and repeated configuration. Android tests also exercise real loopback sockets, lifecycle suspension and outbox migration.

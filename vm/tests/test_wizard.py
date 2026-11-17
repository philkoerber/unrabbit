import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from unrabbit_vm.__main__ import service_arguments
from unrabbit_vm.config import load_config, save_config
from unrabbit_vm.network import ConnectionHint
from unrabbit_vm.setup_qr import connection_payload, make_qr, terminal_lines
from unrabbit_vm.wizard import (AgentOption, CancelSetup, Terminal, check_codex, discover_agents,
                                main, run_wizard)


class WizardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.path = self.root / "settings/vm.json"
        self.fingerprint = "SHA256:" + "A" * 43
        self.agents = [AgentOption("codex", "Codex", "/opt/codex", True),
                       AgentOption("claude", "Claude Code", "/opt/claude", False)]

    def wizard(self, answers, agents=None, hint=None):
        output = io.StringIO()
        terminal = Terminal(io.StringIO(answers), output)
        with patch("unrabbit_vm.wizard.discover_agents", return_value=self.agents if agents is None else agents), \
             patch("unrabbit_vm.wizard.check_codex", return_value=None), \
             patch("unrabbit_vm.wizard.detect_connection", return_value=hint), \
             patch("unrabbit_vm.wizard.default_state_path", return_value=self.root / "state"):
            result = run_wizard(self.path, terminal, "unrabbit")
        return result, output.getvalue()

    def test_detected_host_but_separate_application_ssh_port(self):
        hint = ConnectionHint("203.0.113.10", 22, "SSH connection")
        self.wizard(f"2\n\n\n{self.root}/work\n\n", hint=hint)
        saved = load_config(self.path)
        self.assertEqual("203.0.113.10", saved["connection"]["host"])
        self.assertEqual(2222, saved["connection"]["port"])
        self.assertEqual(2, saved["version"])
        self.wizard("\nvm.example.net\n2200\n\n\n", hint=hint)
        self.wizard("\n\n\n\n\n", hint=hint)
        self.assertEqual("vm.example.net", load_config(self.path)["connection"]["host"])
        self.assertEqual(2200, load_config(self.path)["connection"]["port"])

    def test_address_and_unprivileged_port_cannot_be_skipped(self):
        config, output = self.wizard(f"2\n\n-\nvm.example.net\n22\n2222\n{self.root}/work\n\n")
        self.assertEqual("vm.example.net", config["connection"]["host"])
        self.assertEqual(2222, config["connection"]["port"])
        self.assertIn("port of 1024 or higher", output)
        self.assertNotIn("local_vm.py attach", output)

    def test_complete_config_uses_own_persistent_host_identity_and_no_invitation(self):
        config, output = self.wizard(f"1\nvm.example.net\n2222\n{self.root}/work with spaces\n\n")
        self.assertEqual("codex", config["agent"])
        self.assertEqual("/opt/codex", config["executable"])
        self.assertEqual(0o600, stat.S_IMODE(self.path.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(self.path.parent.stat().st_mode))
        self.assertTrue(config["connection"]["hostKeyFingerprint"].startswith("SHA256:"))
        self.assertNotIn("pairingToken", json.dumps(config))
        self.assertFalse(self.path.with_suffix(".qr.png").exists())  # Issued only after listener starts.
        self.assertFalse((self.root / "state/unrabbit.sqlite3").exists())
        _, args = service_arguments(["run", "--config", str(self.path)])
        self.assertEqual(config, args.saved)

    def test_rerun_keeps_identity_and_cancel_leaves_existing_config_intact(self):
        self.wizard(f"2\nvm.example.net\n\n{self.root}/work\n\n")
        before = self.path.read_bytes()
        self.wizard("\n\n\n\n\n")
        self.assertEqual(before, self.path.read_bytes())
        with self.assertRaises(CancelSetup):
            self.wizard("\nchanged.example.net\n\n\nq\n")
        self.assertEqual(before, self.path.read_bytes())

    def test_eof_and_quit_leave_no_configuration(self):
        for answers in ("", "q\n", "1\nq\n"):
            with self.assertRaises(CancelSetup):
                self.wizard(answers)
            self.assertFalse(self.path.exists())

    def test_agent_detection_never_executes_found_programs(self):
        for name in ("codex", "claude", "gemini", "opencode"):
            executable = self.root / name
            executable.write_text("#!/bin/sh\nexit 99\n")
            executable.chmod(0o700)
        with patch.dict(os.environ, {"PATH": str(self.root)}), patch("subprocess.run", side_effect=AssertionError("must not run")):
            found = discover_agents()
        self.assertEqual(["codex", "claude", "gemini", "opencode"], [item.name for item in found])
        self.assertEqual(["codex"], [item.name for item in found if item.supported])

    def test_codex_probe_checks_contract_and_login_without_model_or_diagnostics(self):
        help_text = "--ignore-user-config --ephemeral --strict-config --output-last-message --image"
        with patch("subprocess.run", side_effect=[subprocess.CompletedProcess([], 0, help_text),
                                                  subprocess.CompletedProcess([], 1)]) as run:
            result = check_codex("/opt/codex")
        self.assertIn("codex login", result)
        self.assertEqual(["/opt/codex", "exec", "--help"], run.call_args_list[0].args[0])
        self.assertEqual(["/opt/codex", "login", "status"], run.call_args_list[1].args[0])
        self.assertEqual(subprocess.DEVNULL, run.call_args_list[1].kwargs["stderr"])
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, "old CLI")) as run:
            self.assertIn("Update the CLI", check_codex("/opt/codex"))
            self.assertEqual(1, run.call_count)

    def test_terminal_qr_has_explicit_contrast_and_complete_quiet_zone(self):
        qr = make_qr(connection_payload("vm.example.net", 22, "unrabbit", self.fingerprint))
        matrix = qr.get_matrix()
        reverse = {" ": (False, False), "▀": (True, False), "▄": (False, True), "█": (True, True)}
        restored = []
        for line in terminal_lines(qr):
            self.assertTrue(line.startswith("\033[30;107m"))
            self.assertTrue(line.endswith("\033[0m"))
            cells = [reverse[cell] for cell in line[len("\033[30;107m"):-len("\033[0m")]]
            restored.extend([[cell[0] for cell in cells], [cell[1] for cell in cells]])
        self.assertEqual(matrix, restored[:len(matrix)])
        self.assertTrue(all(not any(row) for row in restored[:4]))
        self.assertTrue(all(not any(row) for row in restored[-4:]))

    def test_narrow_terminal_can_use_png_instead_of_wrapping_qr(self):
        class Utf8Output(io.StringIO):
            encoding = "utf-8"
        output = Utf8Output()
        terminal = Terminal(io.StringIO("p\n"), output)
        terminal.tty = True
        with patch("shutil.get_terminal_size", return_value=os.terminal_size((40, 24))):
            terminal.qr(make_qr(connection_payload("vm.example.net", 22, "unrabbit")), Path("setup.png"))
        self.assertIn("setup.png", output.getvalue())
        self.assertNotIn("\033[30;107m", output.getvalue())

    def test_configuration_rejects_unknown_fields_duplicates_and_unsafe_overrides(self):
        self.wizard(f"2\nvm.example.net\n\n{self.root}/work\n\n")
        config = load_config(self.path)
        for changed in ({**config, "agent": "claude"}, {**config, "port": True}, {**config, "token": "no"},
                        {**config, "workspace": "relative"}, {**config, "connection": None},
                        {**config, "connection": {**config["connection"], "privateKey": "no"}}):
            with self.assertRaises(ValueError):
                save_config(self.path, changed)
        self.assertEqual(config, load_config(self.path))
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            service_arguments(["--config", str(self.path), "--listen", "0.0.0.0"])
        self.path.write_text(json.dumps(config)[:-1] + ',"agent":"demo"}')
        with self.assertRaises(ValueError):
            load_config(self.path)

    def test_atomic_save_does_not_follow_destination_symlink(self):
        self.wizard(f"2\nvm.example.net\n\n{self.root}/work\n\n")
        config = load_config(self.path)
        other = self.root / "leave-alone"
        other.write_text("keep")
        self.path.unlink()
        self.path.symlink_to(other)
        save_config(self.path, config)
        self.assertEqual("keep", other.read_text())
        self.assertFalse(self.path.is_symlink())

    def test_wizard_refuses_root_and_non_linux_before_changing_files(self):
        for platform, uid in (("linux", 0), ("darwin", 1000)):
            with patch("sys.platform", platform), patch("os.geteuid", return_value=uid), \
                 contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                main(["--config", str(self.path)])
            self.assertFalse(self.path.exists())


if __name__ == "__main__":
    unittest.main()

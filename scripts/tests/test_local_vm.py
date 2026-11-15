import contextlib
import argparse
import io
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local_vm
import check_local_vm


class LocalVmTests(unittest.TestCase):
    def test_recovery_smoke_refuses_a_force_stop_that_leaves_the_process_alive(self):
        with patch.object(check_local_vm.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 0)]) as run, \
             contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            check_local_vm.stop_app(["adb", "-s", "emulator-5572"], argparse.ArgumentParser())
        self.assertEqual(2, run.call_count)
        with patch.object(check_local_vm.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 1)]):
            check_local_vm.stop_app(["adb", "-s", "emulator-5572"], argparse.ArgumentParser())

    def test_qr_pairing_or_atomic_backup_blocks_debug_connection_changes(self):
        for codes in ((0,), (1, 0)):
            with patch.object(local_vm.subprocess, "run", side_effect=[subprocess.CompletedProcess([], code) for code in codes]) as run, \
                 contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                local_vm.require_unpaired(["adb", "-s", "emulator-5572"], argparse.ArgumentParser())
            self.assertTrue(all("test" in call.args[0] for call in run.call_args_list))

    def test_pending_requests_block_agent_switch_before_build_or_restart(self):
        with patch.object(local_vm, "run", side_effect=[
            subprocess.CompletedProcess([], 0, b"running-container\n"),
            subprocess.CompletedProcess([], 0, b"1\n"),
        ]) as run, contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            local_vm.main(["up", "--agent", "codex"])
        self.assertEqual(2, run.call_count)
        self.assertTrue(all("build" not in call.args[0] and "up" not in call.args[0] for call in run.call_args_list))

    def test_missing_login_does_not_replace_running_demo(self):
        with patch.object(local_vm, "run", side_effect=[
            subprocess.CompletedProcess([], 0, b"running-container\n"),
            subprocess.CompletedProcess([], 0, b"0\n"),
            subprocess.CompletedProcess([], 0),
        ]) as run, patch.object(local_vm.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)) as login, \
             contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            local_vm.main(["up", "--agent", "codex"])
        self.assertTrue(all("up" not in call.args[0] for call in run.call_args_list))
        self.assertEqual(["login", "status"], login.call_args.args[0][-2:])
        self.assertEqual(subprocess.DEVNULL, login.call_args.kwargs["stderr"])

    def test_login_uses_device_flow_without_starting_worker_or_copying_host_auth(self):
        with patch.object(local_vm, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            local_vm.main(["login"])
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(2, len(commands))
        self.assertIn("compose.codex.yaml", commands[-1])
        self.assertEqual(["login", "--device-auth"], commands[-1][-2:])
        self.assertIn("--rm", commands[-1])
        self.assertNotIn("--with-api-key", commands[-1])
        self.assertTrue(all("up" not in command and "cp" not in command and "--volume" not in command for command in commands))

    def test_wizard_selects_codex_only_when_explicitly_requested(self):
        for options, codex in (([], False), (["--agent", "codex"], True)):
            with patch.object(local_vm, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
                local_vm.main(["wizard"] + options)
            for call in run.call_args_list:
                self.assertEqual(codex, "compose.codex.yaml" in call.args[0])


if __name__ == "__main__":
    unittest.main()

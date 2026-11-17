import os
import socket
import unittest
from unittest.mock import MagicMock, patch

from unrabbit_vm.network import detect_connection, usable_address


class AddressDetectionTests(unittest.TestCase):
    def test_ssh_uses_server_address_and_port_not_the_client(self):
        with patch("unrabbit_vm.network.is_container", return_value=False), \
             patch.dict(os.environ, {"SSH_CONNECTION": "198.51.100.20 54001 203.0.113.10 2222"}), \
             patch("socket.socket") as create:
            hint = detect_connection()
        self.assertEqual(("203.0.113.10", 2222, "SSH connection"), (hint.host, hint.port, hint.source))
        create.assert_not_called()

    def test_no_external_service_or_dns_needed_for_route_fallback(self):
        probe = MagicMock()
        probe.getsockname.return_value = ("10.1.2.3", 45000)
        manager = MagicMock()
        manager.__enter__.return_value = probe
        with patch("unrabbit_vm.network.is_container", return_value=False), \
             patch.dict(os.environ, {"SSH_CONNECTION": "broken"}), \
             patch("socket.socket", return_value=manager), \
             patch("socket.getaddrinfo", side_effect=AssertionError("No DNS lookup")):
            hint = detect_connection()
        self.assertEqual(("10.1.2.3", 22), (hint.host, hint.port))
        probe.send.assert_not_called()
        probe.sendto.assert_not_called()
        probe.sendall.assert_not_called()

    def test_ipv6_route_is_used_when_ipv4_is_unavailable(self):
        manager = MagicMock()
        manager.__enter__.return_value.getsockname.return_value = ("2001:db8::10", 45000, 0, 0)
        with patch("unrabbit_vm.network.is_container", return_value=False), \
             patch.dict(os.environ, {"SSH_CONNECTION": ""}), \
             patch("socket.socket", side_effect=[OSError("no IPv4"), manager]) as create:
            hint = detect_connection()
        self.assertEqual("2001:db8::10", hint.host)
        self.assertEqual(socket.AF_INET6, create.call_args.args[0])

    def test_unusable_addresses_and_invalid_ssh_port_do_not_become_defaults(self):
        for value in ("", "localhost", "0.0.0.0", "127.0.0.1", "::1", "::ffff:127.0.0.1",
                      "224.0.0.1", "fe80::123%eth0", "169.254.1.2", "240.0.0.1"):
            self.assertIsNone(usable_address(value), value)
        with patch("unrabbit_vm.network.is_container", return_value=False), \
             patch.dict(os.environ, {"SSH_CONNECTION": "198.51.100.20 54001 203.0.113.10 99999"}), \
             patch("socket.socket", side_effect=OSError("offline")):
            self.assertIsNone(detect_connection())

    def test_container_never_advertises_its_internal_ip_or_forwarded_ssh_environment(self):
        with patch("unrabbit_vm.network.is_container", return_value=True), \
             patch.dict(os.environ, {"SSH_CONNECTION": "198.51.100.20 54001 172.18.0.2 22"}), \
             patch("socket.socket") as create:
            self.assertIsNone(detect_connection())
        create.assert_not_called()


if __name__ == "__main__":
    unittest.main()

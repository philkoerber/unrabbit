"""Suggest this machine's address without contacting an external IP service."""
from dataclasses import dataclass
import ipaddress
import os
from pathlib import Path
import socket


@dataclass(frozen=True)
class ConnectionHint:
    host: str
    port: int
    source: str


def is_container():
    return bool(os.environ.get("container")) or Path("/.dockerenv").exists() or Path("/run/.containerenv").exists()


def usable_address(value):
    try:
        address = ipaddress.ip_address(value)
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
            address = address.ipv4_mapped
        if address.is_loopback or address.is_unspecified or address.is_multicast or address.is_link_local or address.is_reserved:
            return None
        return str(address)
    except ValueError:
        return None


def detect_connection():
    # Container addresses and forwarded host SSH variables are not a device endpoint.
    if is_container():
        return None
    fields = os.environ.get("SSH_CONNECTION", "").split()
    if len(fields) == 4:
        try:
            ipaddress.ip_address(fields[0])
            host, port = usable_address(fields[2]), int(fields[3])
            if host and 1 <= int(fields[1]) <= 65535 and 1 <= port <= 65535:
                return ConnectionHint(host, port, "SSH-Verbindung")
        except ValueError:
            pass
    # UDP connect selects a local route/address; no datagram is sent, no DNS is used.
    for family, destination in ((socket.AF_INET, ("192.0.2.1", 9)),
                                (socket.AF_INET6, ("2001:db8::1", 9))):
        try:
            with socket.socket(family, socket.SOCK_DGRAM) as probe:
                probe.connect(destination)
                host = usable_address(probe.getsockname()[0])
                if host:
                    return ConnectionHint(host, 22, "Netzwerk")
        except OSError:
            continue
    return None

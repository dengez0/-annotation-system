import ipaddress
import os
import socket


ADMIN_IP_ENV = 'SIMPLELABEL_ADMIN_IP'


def get_admin_ip():
    """Return the configured startup IPv4 address, or None when unavailable."""
    value = (os.environ.get(ADMIN_IP_ENV) or '').strip()
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return None
    return str(address) if address.version == 4 else None


def get_local_ipv4_addresses():
    """Return IPv4 addresses currently assigned to this server."""
    addresses = {'127.0.0.1'}
    try:
        hostname = socket.gethostname()
        addresses.update(socket.gethostbyname_ex(hostname)[2])
        addresses.update(
            item[4][0]
            for item in socket.getaddrinfo(hostname, None, socket.AF_INET)
        )
    except OSError:
        # The explicit loopback address remains available when name resolution fails.
        pass
    return addresses


def is_admin_ip(client_ip):
    """Allow the configured administrator and requests originating on this host."""
    try:
        address = ipaddress.ip_address(client_ip)
    except (TypeError, ValueError):
        return False

    if address.is_loopback:
        return True

    return str(address) in {get_admin_ip(), *get_local_ipv4_addresses()}

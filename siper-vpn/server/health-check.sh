#!/usr/bin/env bash
set -Eeuo pipefail
WG_IF="${WG_IF:-wg0}"

echo "=== interface ==="
wg show "${WG_IF}"

echo
echo "=== forwarding ==="
sysctl net.ipv4.ip_forward

echo
echo "=== UDP listener ==="
ss -lunp | grep -E ":(443|51820)\b" || true

echo
echo "=== nftables ==="
nft list table inet siper 2>/dev/null || true

echo
echo "=== recent handshakes ==="
wg show "${WG_IF}" latest-handshakes

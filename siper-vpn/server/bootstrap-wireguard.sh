#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

WG_IF="${WG_IF:-wg0}"
WG_PORT="${WG_PORT:-443}"
WG_NET="${WG_NET:-10.77.0.0/24}"
SERVER_ADDR="${SERVER_ADDR:-10.77.0.1/24}"
CLIENT_ADDR="${CLIENT_ADDR:-10.77.0.2/32}"
CLIENT_NAME="${CLIENT_NAME:-siper-android}"
DNS1="${DNS1:-1.1.1.1}"
DNS2="${DNS2:-9.9.9.9}"
MTU="${MTU:-1380}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root" >&2
  exit 1
fi

PUBLIC_IF="$(ip -4 route show default | awk '/default/ {print $5; exit}')"
if [[ -z "${PUBLIC_IF}" ]]; then
  echo "No default IPv4 route found" >&2
  exit 1
fi

PUBLIC_IP="$(curl -4fsS --max-time 10 https://api.ipify.org || true)"
if [[ -z "${PUBLIC_IP}" ]]; then
  PUBLIC_IP="$(ip -4 addr show dev "${PUBLIC_IF}" | awk '/inet / {print $2}' | cut -d/ -f1 | head -1)"
fi

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y wireguard nftables curl qrencode ca-certificates

install -d -m 700 /etc/wireguard/keys
install -d -m 700 "/root/${CLIENT_NAME}"

SERVER_PRIV="/etc/wireguard/keys/server.key"
SERVER_PUB="/etc/wireguard/keys/server.pub"
CLIENT_PRIV="/root/${CLIENT_NAME}/client.key"
CLIENT_PUB="/root/${CLIENT_NAME}/client.pub"
CLIENT_PSK="/root/${CLIENT_NAME}/client.psk"

[[ -f "${SERVER_PRIV}" ]] || wg genkey | tee "${SERVER_PRIV}" | wg pubkey > "${SERVER_PUB}"
[[ -f "${CLIENT_PRIV}" ]] || wg genkey | tee "${CLIENT_PRIV}" | wg pubkey > "${CLIENT_PUB}"
[[ -f "${CLIENT_PSK}" ]] || wg genpsk > "${CLIENT_PSK}"

chmod 600 "${SERVER_PRIV}" "${CLIENT_PRIV}" "${CLIENT_PSK}"
chmod 644 "${SERVER_PUB}" "${CLIENT_PUB}"

SERVER_PRIVATE_KEY="$(cat "${SERVER_PRIV}")"
SERVER_PUBLIC_KEY="$(cat "${SERVER_PUB}")"
CLIENT_PRIVATE_KEY="$(cat "${CLIENT_PRIV}")"
CLIENT_PUBLIC_KEY="$(cat "${CLIENT_PUB}")"
PRESHARED_KEY="$(cat "${CLIENT_PSK}")"

cat >/etc/sysctl.d/99-siper-vpn.conf <<'EOF'
net.ipv4.ip_forward=1
net.ipv4.conf.all.src_valid_mark=1
net.ipv4.conf.all.rp_filter=2
net.ipv4.conf.default.rp_filter=2
EOF
sysctl --system >/dev/null

cat >"/etc/wireguard/${WG_IF}.conf" <<EOF
[Interface]
Address = ${SERVER_ADDR}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIVATE_KEY}
MTU = ${MTU}
SaveConfig = false

PostUp = nft add table inet siper 2>/dev/null || true; nft 'add chain inet siper forward { type filter hook forward priority 0; policy drop; }' 2>/dev/null || true; nft 'add chain inet siper nat { type nat hook postrouting priority srcnat; policy accept; }' 2>/dev/null || true; nft add rule inet siper forward iifname "${WG_IF}" oifname "${PUBLIC_IF}" ct state new,established,related accept 2>/dev/null || true; nft add rule inet siper forward iifname "${PUBLIC_IF}" oifname "${WG_IF}" ct state established,related accept 2>/dev/null || true; nft add rule inet siper nat oifname "${PUBLIC_IF}" ip saddr ${WG_NET} masquerade 2>/dev/null || true
PostDown = nft delete table inet siper 2>/dev/null || true

[Peer]
PublicKey = ${CLIENT_PUBLIC_KEY}
PresharedKey = ${PRESHARED_KEY}
AllowedIPs = ${CLIENT_ADDR}
EOF

chmod 600 "/etc/wireguard/${WG_IF}.conf"
systemctl enable "wg-quick@${WG_IF}"
systemctl restart "wg-quick@${WG_IF}"

cat >"/root/${CLIENT_NAME}/siper.conf" <<EOF
[Interface]
PrivateKey = ${CLIENT_PRIVATE_KEY}
Address = ${CLIENT_ADDR}
DNS = ${DNS1}, ${DNS2}
MTU = ${MTU}

[Peer]
PublicKey = ${SERVER_PUBLIC_KEY}
PresharedKey = ${PRESHARED_KEY}
Endpoint = ${PUBLIC_IP}:${WG_PORT}
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
chmod 600 "/root/${CLIENT_NAME}/siper.conf"

echo
echo "Siper VPN node is active."
wg show "${WG_IF}"
echo
echo "Client config: /root/${CLIENT_NAME}/siper.conf"
echo "Allow UDP ${WG_PORT} in the VPS/cloud firewall."
echo
qrencode -t ANSIUTF8 < "/root/${CLIENT_NAME}/siper.conf" || true

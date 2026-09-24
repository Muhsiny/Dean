# Siper VPN node

This is the server half of the Android client. It provisions a real WireGuard endpoint; the Android app does not show "connected" merely because a button was tapped. Its backend reports transport state and handshake health.

## Deploy

Use a clean Ubuntu VPS with a public IPv4 address. Copy `bootstrap-wireguard.sh` to it and run:

```bash
sudo WG_PORT=443 bash bootstrap-wireguard.sh
```

Open **UDP 443** in the cloud firewall/security group. The generated Android profile is:

```
/root/siper-android/siper.conf
```

Import that profile into Siper VPN. A healthy connection is one in which the app backend reaches `Tunnel.State.Up.Healthy` and `wg show` reports a recent handshake.

## Security choices

- WireGuard kernel/userspace backend
- Per-client key pair
- WireGuard preshared key in addition to Curve25519 keys
- nftables forwarding policy defaults to drop
- Only VPN-client egress and established return traffic are forwarded
- Client profile is mode 0600
- Android stores imported profile encrypted with AES-256-GCM via Android Keystore
- No analytics/ads/account SDK is included in the Android build

Run `health-check.sh` after deployment and after network/firewall changes.

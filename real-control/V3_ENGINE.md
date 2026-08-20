WiFi Control Real v3

- Target firmware family: TP-Link TD-W8961N V4 / TrendChip legacy UI.
- Native apps never treat the router MAC as a client.
- Manager MAC protection remains in the Android/Windows controller.
- Wireless control uses the real Wireless MAC Address Filter and safe SAVE + read-back verification.
- Internet control uses Access Management IP/MAC filtering separately from Wi-Fi association control.
- Anti-QR uses Allow Association rather than pretending the password itself cannot be shared.
- QoS is priority/queue control, not falsely labelled as exact per-client Mbps.
- Guest bandwidth is used only when the firmware exposes real upstream/downstream fields.
- Unsupported controls remain disabled.
- The v3 patch refuses DELETE, RESET, REBOOT and UPGRADE as SAVE targets.

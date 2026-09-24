Siper Fusion includes Psiphon tunnel-core as a censorship-resistant fallback transport.

Psiphon tunnel-core
Copyright (c) Psiphon Inc.
License: GNU General Public License version 3 or later (GPL-3.0-or-later)
Source: https://github.com/Psiphon-Labs/psiphon-tunnel-core
Android library version used by this build: 2.0.41

The Psiphon bootstrap data packaged at build time is public client bootstrap data and is
checked against a pinned SHA-256 before packaging. Runtime server entries are validated
with the public signature key configured in PsiphonFallbackConfig.

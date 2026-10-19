# Third-party notices

PerRoute starts or manages the following independent programs and libraries:

- Xray-core — Mozilla Public License 2.0 — https://github.com/XTLS/Xray-core
- Wintun — license terms published by WireGuard LLC — https://www.wintun.net/
- FlatLaf — Apache License 2.0 — https://github.com/JFormDesigner/FlatLaf
- ProxiFyre — GNU Affero General Public License 3.0 — https://github.com/wiresock/proxifyre
- Windows Packet Filter / NDISAPI 3.6.2.1 - https://github.com/wiresock/ndisapi
- Microsoft Visual C++ 2015-2022 Redistributable 14.44.35211.0 - https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist

The Full Installer embeds unmodified official x64 installers for ProxiFyre,
Windows Packet Filter, and the Visual C++ Runtime. Their pinned SHA-256 digests
are verified while packaging and again before execution. ProxiFyre remains a
separate product and Windows service. Its corresponding source code and license
information are available from the repository above. Redistributors are
responsible for satisfying the AGPL-3.0 source-offer obligations and the license
terms that apply to Windows Packet Filter and the Microsoft runtime.

## Java Native Access (JNA)

PerRoute bundles the unmodified JNA 5.17.0 library and its `jnidispatch.dll`
stub to call Windows DPAPI in-process. JNA is dual-licensed under the Apache
License 2.0 and the LGPL 2.1 or later; PerRoute uses it under Apache-2.0.
Source: https://github.com/java-native-access/jna

## Browser extension and runtime

The browser runtime bundles the unmodified official Xray-core release (pinned by SHA-256)
and statically links the Rust crates listed in browser/licenses/THIRD-PARTY.md. The
extension bundles jsQR (Apache License 2.0) and the Inter typeface (SIL OFL 1.1). The
license texts are installed with the runtime in its `licenses` folder.

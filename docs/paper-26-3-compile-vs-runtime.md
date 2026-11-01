# Paper 26.3 compatibility evidence — DynaTech

The Java 21 universal plugin remains built against Paper 1.21.11. The separate
Paper 26.3 Maven compatibility lane now targets the **pinned stable API**
`26.3.build.205-stable`, rather than the old `26.3-rc-3.build.1-alpha`.
This is **compiler/test evidence only**, not a claim that old worlds can be
upgraded or rolled back safely.

The existing native wireless transfer/restart probe intentionally retains its
historical, checksum-pinned Paper 26.3 **BETA build 159** fixture in
`scripts/runtime/wireless_probe_inputs.json`. Its green result must not be
described as stable 26.3 native gameplay/restart proof. Do not replace this pin
with an unverified latest build or reuse its earlier result for a new pin.

Before claiming stable 26.3 native compatibility, record a new official stable
Paper server build ID, metadata channel, download URL, size and SHA-256;
validate the exact downloaded bytes; then execute the existing source-pinned
wireless input/output, item conservation, and restart probes against that
server, the exact Legacy core and addon candidate. Retain both old and new
reports, and require review before changing the published release contract.

No recipe, energy, storage, transfer, plugin metadata, public API or release
version is changed by this compiler-lane modernization.

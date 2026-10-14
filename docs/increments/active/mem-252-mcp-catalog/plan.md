# Implementation plan

Design: [design.md](design.md). Rides on the MEM-251 pull request; no API or migration change.

- [x] Probe the twelve addresses and their authorization servers; check the JDK user agent from staging.
- [x] Catalog entries with scopes, Google parameters and console links; "Đã thêm" and "Ứng dụng phổ biến" on
  `/admin/mcp`; the add dialog filled in from an entry; the new server's page after saving.
- [x] The manual OAuth form shows the provider's console link and the callback address.
- [x] A newly listed read-only tool starts on; chat spec and matrix.
- [x] Component and browser tests; screenshots reviewed.
- [ ] Owner approval of the screenshots.
- [ ] Staging: a person connects Linear and lists its tools; record it in the runbook; then the other automatic
  entries as accounts allow.

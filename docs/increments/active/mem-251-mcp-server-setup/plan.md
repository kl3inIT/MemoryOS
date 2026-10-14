# Implementation plan

Design: [design.md](design.md). One pull request; web only, no API or migration change.

## 1. Server dialog

- [x] Order: URL, name, sign-in, whose account, who may use it; radio choices with a visible selected state.
- [x] Advanced section: derived and editable short code, discovery or manual OAuth application, scopes, additional
  parameters with the Google button, callback address, header rows (replace or remove on an existing server),
  description.
- [x] The payload sends the edited scopes, parameters and headers; the acknowledgement checkbox is removed.

## 2. Server list

- [x] Two-column list with brand marks, one line and one action; details in a side sheet.
- [x] Next-step rule from the server, its OAuth applications and the administrator's own connection; the line names
  what is missing.
- [x] One "create automatically" discovery action; the manual form prefilled from discovery.
- [x] Confirmation before deleting an OAuth application and ending the shared connection.

## 3. Settings › Connections

- [x] "Ứng dụng đã kết nối" in the same list, "Kết nối lại", one menu for several applications, translations.

## 4. Verification and documents

- [x] Component tests and the MCP administration browser suite (run locally); typecheck, lint, format and i18n
  audit on the changed files. The whole `pnpm --dir web check` (knip runs out of memory locally) is left to CI.
- [ ] Screenshots in both themes reviewed; owner approval.
- [x] Runbook: the Google parameters are entered under Advanced. Roadmap row.
- [ ] Staging: Google Drive MCP added from the form keeps working past an hour.

# MEM-217 — "Tenant" in UI copy becomes "tổ chức" / "organization"

Linear: [MEM-217](https://linear.app/memory-os/issue/MEM-217). This is the last open item from the 2026-10-02 `/impeccable critique`. Frontend copy only, with a guard in `check:i18n`.

## Problem

The interface named the same thing two ways:

- **English.** 41 source sentences said "Tenant", a domain term. Four more quote Microsoft's *Directory (tenant) ID*.
- **Vietnamese.** 28 sentences already said "tổ chức", and 18 still said "Tenant". The two forms sat side by side on one page: the admin group was *Tổ chức*, while the Models page said *Mô hình mặc định của Tenant*.

Owner decision (2026-10-03):

- Copy says **tổ chức** in Vietnamese and **organization** in English.
- The domain model, API, database, code identifiers and engineering documents keep **Tenant**.

## Glossary

Only the term changes. Sentences are not otherwise rewritten.

| Before | English | Vietnamese |
| --- | --- | --- |
| Tenant | organization | tổ chức |
| Tenant owner | organization owner | chủ sở hữu tổ chức |
| tenant administrator | organization administrator | quản trị viên tổ chức |
| Tenant-wide | organization-wide | toàn tổ chức |
| Tenant default (models) | organization default | mặc định của tổ chức |
| Tenant member / user | organization member / user | thành viên / người dùng trong tổ chức |
| Admin menu group *Tenant* | *Organization*, through the existing key `"Tổ chức"` | *Tổ chức* |

## Exclusions

- **Microsoft Entra field label.** *Directory (tenant) ID* is the label Microsoft uses. Its four sentences quote it verbatim.
- **SharePoint test result.** *{{v1}} can read this Tenant's sites* meant the Microsoft 365 tenant, not the MemoryOS organization. It becomes *{{v1}} can read every SharePoint site* / *{{v1}} đọc được mọi site SharePoint*.
- **Not copy.** Code comments, identifiers (`tenant.role`, `id: "tenant"`, `tenant-mark.png`) and test titles are not copy.
- **Server text.** Server-side messages that mention the Tenant never reach the screen. The UI shows its own copy for problem codes, and the search-settings conflict detail carries no Tenant wording. The capability descriptions come from `group-capability-copy.ts`, not from the API.

## Change

- **English source keys.** One old → new mapping is applied with an exact-count check: each English source key changes at its call sites, its `app-translations.vi.ts` key and its Vietnamese value. Seven of these keys have no call site left. They are renamed for consistency, not deleted.
- **Vietnamese source keys.** Four Vietnamese source keys get their `app-translations.en.ts` value changed. Two of them also change their key and call site.
- **Session catalog.** The session catalog's `identity.notProvisionedDescription` (`i18n/en.ts`) changes too.
- **Guard.** `check:i18n` fails a catalog line whose string says *tenant*, apart from *Directory (tenant) ID*. On the old catalogs it reports 58 lines.

## Acceptance

- No catalog string says Tenant, apart from the Entra field label.
- `check:i18n`, `tsc -b`, lint, format and the affected unit and e2e tests pass.
- The owner approves the old → new wording table in the pull request.

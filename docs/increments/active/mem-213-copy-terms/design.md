# MEM-213 One tone style, one name per concept

Second fix batch of the 2026-10-02 UI critique; copy only, no behavior change. Owner decisions 2026-10-02: the older tone style (*xóa*), **Chat**, **Quay lại ứng dụng**, **Nguồn dữ liệu**, and *lập chỉ mục* kept as it is.

## Design

- **Tone style.** An open *oa*, *oe* or *uy* carries the tone on its first vowel (*xóa*, *khóa*, *hủy*, *tùy*, *hóa*): 297 words in 55 files, Vietnamese source keys, their English entries and tests included. Five English-catalog entries that became duplicates were merged (same values). `check:i18n` now fails on a second-vowel tone in `vi.ts`, `app-translations.vi.ts` and `app-translations.en.ts` (*quý* is not such a pair).
- **One name per concept.** Chat keeps its English name in Vietnamese copy; the owner role is *Chủ sở hữu* (English *Owner*/*Member* instead of *Tenant owner*/*Tenant member*); *Back to MemoryOS* becomes *Back to the app* / *Quay lại ứng dụng*; the Sources page is *Sources* / *Nguồn dữ liệu* in the navigation and on the page.
- **Users search.** The visible label *Search users* names the field; the duplicate `aria-label` is removed (WCAG 2.5.3).

## Sources list follow-up (owner, 2026-10-03)

- Access is plain secondary text with its icon (`SourceAccess`, formerly `SourceAccessBadge`), not an outline badge: it is neither a state nor a control, and the frame weighed as much as the status badge.
- A provider's group row shows only its chevron, icon and name. The visible "3 sources · 129 documents" repeated the rows below and the documents total above; the counts stay in the toggle's accessible name. The *1 source*/*{{count}} sources* keys are removed.

## Out of scope

"Tenant" in about 50 other sentences (both languages), Chat (batch 3), minor findings (batch 4).

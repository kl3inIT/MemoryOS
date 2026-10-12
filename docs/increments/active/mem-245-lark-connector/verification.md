# Verification

## Staging, 2026-10-12

Release `4877daeb` (pull request #578), deployed by `Deploy staging` after the main CI run. The Lark tenant is the
test tenant of the design; the readers are benchmark role accounts of staging, whose verified emails were given to
two Lark test users.

| Lark person | Lark sharing | MemoryOS account |
| --- | --- | --- |
| Lan, in "Kinh doanh miền Bắc" below "Kinh doanh" | viewer of the Drive folder; the Q3 report is shared with "Kinh doanh" | `bm-finance@memoryos.test` |
| Minh | member of the Wiki space | `bm-legal@memoryos.test` |
| nobody | | `rag-benchmark@memoryos.test`, `admin@memoryos.local` |

Before the deployment the server was given the key file `secrets/encryption/lark-credential-key.txt` and
`MEMORYOS_LARK_CREDENTIAL_KEY_VERSION`; migrations `V152` to `V154` applied and every container was healthy.

| Step | Result |
| --- | --- |
| `POST /api/credentials/lark` | 200; Lark verified the app, the bot was named and no scope was missing |
| `GET /api/sources/lark/credentials/{id}/spaces` | the one space the bot is a member of |
| `POST /api/sources/lark` with the space, the Drive folder and access SYNC | 202; the scope was verified in 6 seconds and the first run started |
| First run | 16 objects acquired and indexed (10 Wiki documents, one of them a sheet, and 6 Drive objects); 16 sharing snapshots read, none failed; the run and the directory generation completed |
| Directory | the space with three members, "Kinh doanh" with Lan through its sub-department, four people asked about, the bot without an email |

Search restricted to Lark Sources (`POST /api/search`), by reader:

| Query | Lan | Minh | Others |
| --- | --- | --- | --- |
| "nghỉ phép" | the Q3 report, "Nội quy văn phòng" and two Drive files | seven Wiki documents | nothing |
| "quy trình" | the Q3 report | ten Wiki documents | nothing |

Lan read the Drive objects and the one Wiki document shared with her department, and no other Wiki document; Minh
read the Wiki documents and no Drive object; an account with no Lark counterpart read nothing, the Tenant owner
included. Every object but one has a link open to the organization (`tenant_readable`), which granted nobody.

Lan was then removed from the Drive folder in Lark and a run requested: it completed in ten seconds and her
searches returned the Q3 report alone, which her department still grants.

Found here and fixed after: a sheet was titled by its stored snapshot name, `Bảng giá dịch vụ 2026.json`.

## Browser review on staging, 2026-10-12

The provider list, the four setup steps, the credential dialog with its guide, the managed credential row and the
Source panel were opened as the Tenant owner at 1440 and 390 pixels wide, in Vietnamese, and their screenshots
read. Three faults were found and fixed after:

- the Source summary said "People with access in Google Drive" for a Lark Source read by Lark's sharing;
- the scope table of the credential guide cut each scope name short, so it could not be read before copying;
- the sentence under the Drive folder field repeated what its placeholder shows.

The fixed screens have not been looked at on staging again; that needs the release that carries them.

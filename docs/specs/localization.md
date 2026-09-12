# Application language and safe problems

The web application supports Vietnamese (default) and English across Chat, attachments, Search/readers, Sources/Drive, Users, Groups, settings, invitations and session screens. Landing and Keycloak themes/emails remain separate. Never translate document text, saved titles, filenames, provider/model names or user input as UI keys.

IAM owns `actors.ui_language` (`vi`/`en`, V41). GET `/api/identity/me` returns it; guarded PUT `/api/identity/me/language` updates only the authenticated active member. Spring Data `JpaActorRepository` handles aggregate persistence; its custom fragment refreshes already-managed state under a lock. IdP profile observations preserve the preference.

The identity query remains authoritative. i18next/react-i18next render without locale-keyed remounts or a parallel locale store. Settings waits for confirmation, reconciles lost responses and ignores late responses after Actor changes. HTML language, dates and numbers follow the account preference. Drafts and open failures survive language changes without replaying mutations.

Shared error descriptors map stable RFC 9457 status/business/validation codes to safe UI copy. Feature `AppText` descriptors carry static keys and inert parameters; only explicitly nested descriptors are translated, plain strings are not. Never display raw backend/provider diagnostics. Toasts belong to operations, not interceptors. Safe unknown error references may remain visible; rejected values, credentials and diagnostics cannot.

Chat snapshots a server-side language hint per turn. Vietnamese preference suggests Vietnamese unless the user asks otherwise; English follows the message language. No retrieval/document translation or Persona override. Saved content keeps its original language.

`pnpm check:i18n` scans direct JSX text, presentation attributes and static `ui`/`appText` keys. Catalog tests enforce key/parameter parity and registry/provider coverage. This AST gate is a regression guard, not proof about arbitrary dynamically assembled copy; browser tests complement it. New sentences should use whole-message interpolation.

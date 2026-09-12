# Chat UX baseline follow-up

Accepted 2026-09-12: correct the audited composer, file picker, editing, Project files, header and Vietnamese copy; add automatic conversation naming and file citation positioning. Reference: reference implementation checkout `40eb240df370688ed6eeedc1272116e7829554ac`, FilePickerPopover, ProjectContextPanel, HumanMessage and chat_session_naming.

Reuse assistant-ui attachment runtime/components, existing Radix popover/dialog and MemoryOS file APIs. One composer action row; paperclip opens upload plus three recent files and a separate full picker. Project Files live on the Project surface, not the create/edit form. Keep attachment editing available through the same compact picker rather than removing existing server capability. Keep all owner/readiness/delete checks.

Naming derives a short title from the first exchange using the resolved, authorized provider. It must be bounded, best-effort, not delay the answer, and never overwrite manual renaming or cause another answer request. Use existing inference/model lifecycle and persistence; no new queue or naming worker. Fall back to a short first-question title. The fallback uses native grapheme segmentation so joined emoji and combining marks stay intact, with both the 40-grapheme display budget and the existing API UTF-16 length constraint enforced.

File evidence records a character window for read_file and an indexed passage for search_files; readers open and highlight that evidence. Whole-file context citations must be identified as whole-file, not fabricated page locations. Existing sources remain readable. Retain signed upload, PostgreSQL claims, JSONB message descriptors, private sharing and deferred OCR scope.

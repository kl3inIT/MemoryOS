# Chat model selector: concrete model identity

Scope: remove the synthetic Auto entry and provider/deployment group headings from the existing assistant-ui model selector. Display the real selected model name and native selected checkmark. At the user's request, omit the context-window subtitle. Keep backend-authorized UUID selection; never hard-code Luna or infer its limits from the reference screenshot.

The existing catalog `isDefault` flag will identify the effective inherited model for the requested Persona (authorized Persona selection, otherwise Tenant default), rather than only the Tenant default. This lets an unset explicit choice display the actual inherited selection without changing send precedence. An inherited hidden model remains absent from the visible list; show an unavailable selection rather than falsely naming another model.

Staging read-only verification on 2026-09-13 found GPT-5.6 Luna already configured as Tenant default, with a 36,096-token context window. No default/credential mutation is needed. Its configured reasoning effort and helper effort are `none`; the model capability is reasoning-enabled. Per-turn reasoning selection is not implemented by this UI correction and must not be represented by a cosmetic control.

Reuse the installed assistant-ui ModelSelector primitives, descriptions, icons, checkmark, search and keyboard behavior. Keep provider names as search terms, not headings. Preserve distinct configuration UUIDs, including identical model labels from multiple connections.

No web-search implementation, OCR changes, PR, commit or deployment is in scope.

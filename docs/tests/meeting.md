# Meetings verification

| Contract | Verification |
| --- | --- |
| A meeting is owner-private: another member gets 404 for read, ticket and delete and sees an empty list; titles and participant lists are trimmed and de-duplicated; an in-person meeting refuses a `TAB` ticket; a ticketed `MIC` socket stores the provider's utterance at the requested offset, answers `ready`, `utterance`, `finished` and closes normally; a spent ticket is refused; speaker naming, stale-revision notes (409), end, the ended-meeting ticket refusal (409 `MEETING_ENDED`), list duration and cascade delete | `ChatSessionApiIntegrationTest.meetingsAreOwnerPrivateAndStoreUtterancesFromATicketedTrackSocket`: full API/security, real PostgreSQL, WebSocket and a loopback OpenAI-compatible provider |
| Soniox live streams send diarization, endpoints, language and context terms; final tokens become segments split by speaker change and `<end>` with the offset applied; finalize ends with an empty frame; a provider error reconnects, replays the last five seconds and shifts times; exhausted retries report one failure | `SonioxLiveTranscriptionTest`: controlled JDK WebSocket boundary |
| Providers without a live protocol get utterances cut at pauses, silence never reaches the provider, the recording clock is kept and a provider error is reported | `ChunkedLiveTranscriptionTest` |
| The meeting module stays closed and depends only on IAM and the voice named interface; its persistence is private | `ModulithArchitectureTest`, `CoreDependencyRulesTest` |
| Meeting routes are documented and CSRF-protected in the committed OpenAPI | `OpenApiContractTest` |

Live Soniox acceptance with real meetings, real microphones and shared tabs remains open, as for MEM-91.

# Implementation plan

- [x] Pin `client-sdk` 1.52.0 as a JAR with `azure-core`.
- [x] `AzureSpeechTarget` (endpoint rule A), `AzureSpeechGateway`, `SpeechSdkGateway`.
- [x] `AzureRealtimeTranscriber` and `AzureVoiceAdapter` as a realtime adapter; SDK read-aloud; REST read-aloud removed.
- [x] API image: native dependencies and asserts.
- [x] Tests through `FakeAzureSpeechGateway`; native load checked locally; Voice tests green.
- [x] Chat spec and architecture describe Azure on the SDK.
- [x] Owner accepted the SDK license; SDK telemetry disabled by default.
- [ ] CI green, including the image build.
- [x] No live-key acceptance (owner decision 2026-09-30); after merge, move this increment to `completed/`.

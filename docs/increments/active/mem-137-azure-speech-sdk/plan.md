# Implementation plan

- [x] Pin `client-sdk` 1.52.0 as a JAR with `azure-core`.
- [x] `AzureSpeechTarget` (endpoint rule A), `AzureSpeechGateway`, `SpeechSdkGateway`.
- [x] `AzureRealtimeTranscriber` and `AzureVoiceAdapter` as a realtime adapter; SDK read-aloud; REST read-aloud removed.
- [x] API image: native dependencies and asserts.
- [x] Tests through `FakeAzureSpeechGateway`; native load checked locally; Voice tests green.
- [x] Chat spec and architecture describe Azure on the SDK.
- [ ] Owner review of the SDK license.
- [ ] CI green, including the image build.
- [ ] Owner acceptance with a real Azure key; then move this increment to `completed/`.

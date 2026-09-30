# Azure Speech through the Speech SDK

Status: **implemented 2026-09-30** on `dathip04/mem-137-azure-speech-sdk`, on top of the Voice adapters of
[provider adapter registries](../provider-adapter-registries/design.md). Linear:
[MEM-137](https://linear.app/memory-os/issue/MEM-137). One PR (owner decision 2026-09-30).

## Problem

[MEM-91](../../completed/mem-91-chat-voice/design.md) decision Q1 kept Azure AI Speech on REST: dictation was chunked
three-second clips and read-aloud one REST request per segment. Onyx streams both through the native Speech SDK, and
Azure's own silence detection can end utterances, which the chunked path cannot.

## Decisions (owner, 2026-09-30)

1. One PR for dictation, read-aloud, locale identification and the image.
2. **Endpoint rule A:** the endpoint an administrator already stored decides how the SDK connects, so no connection is
   reconfigured. A custom-domain resource (`https://name.cognitiveservices.azure.com`) or a container is the SDK
   endpoint (`SpeechConfig.fromEndpoint`); the regional form the portal shows without a custom domain
   (`https://<region>.api.cognitive.microsoft.com`) becomes its region (`SpeechConfig.fromSubscription`).
   `AzureSpeechTarget` holds the rule and its test covers each form.

## Reference

- Northstar `caef9a9`, `integrations/speech-azure`: `AzureSpeechGateway` seam with `SpeechSdkGateway` as the real
  one; `client-sdk` as a JAR; the Liberica image installs `libstdc++ libuuid openssl ca-certificates alsa-lib` and
  asserts the libraries.
- Onyx voice (see the [MEM-91 Onyx reference](../../completed/mem-91-chat-voice/onyx-voice-reference.md)): SDK push
  stream at 16 kHz, continuous language identification among the configured locales, SDK sentence segmentation, SSML
  speech.

## Design

- **Dependency:** `com.microsoft.cognitiveservices.speech:client-sdk:1.52.0` (latest, pinned), taken as its JAR (the
  artifact is published as an AAR) with its one dependency, `azure-core` 1.58.1 (plus `azure-json`, `azure-xml`):
  `SpeechConfig`'s endpoint overloads name `azure-core`'s `TokenCredential`, so it is needed to compile. The JAR is
  about 15 MB and carries the native libraries for Linux, Windows and macOS.
- **License:** the SDK is under the *Microsoft Software License Terms for Microsoft Cognitive Services Speech SDK*
  (https://aka.ms/csspeech/license), not an open-source license. The owner accepted shipping it in the API image on
  2026-09-30. Redistribution to a customer who runs the image needs matching protective terms in that agreement.
- **Telemetry off** (owner decision 2026-09-30): every `SpeechConfig` sets `SPEECH-TelemetryDataEnabled=false`, the
  switch Microsoft's SDK README names, so only the audio and text a request needs leave MemoryOS. Microsoft cannot
  analyze a support request in detail while it is off; it can be turned on for one.
- **Seam:** `AzureSpeechGateway` (package-private in `voice`) is every SDK call; `SpeechSdkGateway` is the real one,
  `FakeAzureSpeechGateway` the test one, so unit tests never load the native library.
- **Dictation:** `AzureVoiceAdapter` implements `RealtimeTranscriptionAdapter`. `AzureRealtimeTranscriber` resamples
  24 kHz to 16 kHz in whole sample triples, writes to the SDK push stream, emits the utterance being spoken as
  interim text and each utterance Azure ends on silence as committed text with `utteranceEnd:true`. The member's
  language is recognized as `vi-VN` or `en-US`; without one, both are identified continuously. Stop closes the
  stream and waits for the last utterance. A session the SDK cannot start, a cancellation or a write failure replays
  the bounded recording through the chunked REST path and counts `memoryos.chat.voice.realtime.fallback`, as OpenAI
  and Soniox do.
- **Read-aloud:** `AzureVoiceAdapter.speech` speaks each segment's SSML (`AzureSpeech.ssml`, shared escaping) with
  24 kHz MP3 output and streams it as synthesized; closing the stream stops the speech in progress. The REST
  read-aloud request is removed.
- **Kept on REST:** connection checks (voice listing), clip transcription used by the fallback, and no uploaded
  recordings for Azure.
- **Image:** only the API stage installs the native dependencies and asserts `libstdc++.so.6` and `libuuid.so.1`;
  the worker loads no SDK. The API image already enables native access.
- **Not in this change:** meeting live streams stay chunked for Azure (the SDK's conversation transcriber could
  separate speakers later); a Tenant setting for spoken languages (the web shows none; the two locales are fixed).
  The web composer does not yet act on `utteranceEnd`; Auto-Send stays tied to Stop.

## Verification

- `AzureVoiceAdapterTest` through the fake gateway: endpoint rule A, locale selection, 24→16 kHz resampling across
  frames, interim and committed transcripts with the utterance boundary, finish after the last utterance, one slot
  release, provider failure falling back to REST with the metric, a session the SDK cannot start, read-aloud across
  segments and stop.
- Native load on Windows: `SpeechConfig.fromEndpoint` and `fromSubscription` created from the real SDK (scratch
  test, not committed).
- CI `backend-images` builds the API image with the library asserts.
- No live-key acceptance: the owner waived it on 2026-09-30.

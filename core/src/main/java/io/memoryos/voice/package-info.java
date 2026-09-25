/**
 * Voice: the Tenant's speech connections, batch and live transcription, and speech synthesis. Audio is never stored.
 * Chat dictation and read-aloud and meeting recordings use it; the provider clients are package-private here.
 */
@ApplicationModule(displayName = "Voice", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "ai", "iam", "audit", "usage"})
package io.memoryos.voice;

import org.springframework.modulith.ApplicationModule;

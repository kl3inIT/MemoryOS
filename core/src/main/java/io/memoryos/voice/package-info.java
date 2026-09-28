/**
 * Voice: the Tenant's speech connections, batch and live transcription, and speech synthesis. Audio is never stored.
 * Chat dictation and read-aloud and meeting recordings use it; the provider clients are package-private here.
 */
@ApplicationModule(displayName = "Voice", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "ai", "iam", "audit", "usage"})
@NullMarked
package io.memoryos.voice;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;

/** Owner-private meetings: live capture or an uploaded recording, transcript utterances, speaker names and notes. */
@ApplicationModule(displayName = "Meetings", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "iam", "ai", "voice", "library", "objectstorage", "usage"})
@NullMarked
package io.memoryos.meeting;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;

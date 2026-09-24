/** Owner-private meetings: live capture or an uploaded recording, transcript utterances, speaker names and notes. */
@ApplicationModule(displayName = "Meetings", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"shared", "iam :: group", "ai", "voice", "library", "objectstorage", "usage"})
package io.memoryos.meeting;

import org.springframework.modulith.ApplicationModule;

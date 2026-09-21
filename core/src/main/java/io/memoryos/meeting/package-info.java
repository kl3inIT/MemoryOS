/** Owner-private meetings: live capture, transcript utterances, speaker names and notes. */
@ApplicationModule(displayName = "Meetings", type = ApplicationModule.Type.CLOSED,
        allowedDependencies = {"iam :: *", "chat :: voice"})
package io.memoryos.meeting;

import org.springframework.modulith.ApplicationModule;

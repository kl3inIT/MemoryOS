package io.memoryos.chat;

import java.util.List;

public record AgentShareOptions(List<AgentPerson> people, List<AgentRef> groups) {}

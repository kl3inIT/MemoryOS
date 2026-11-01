package io.memoryos.iam;

import java.util.List;

public record PrincipalMatches(List<PrincipalPerson> people, List<PrincipalGroup> groups) {
    public PrincipalMatches {
        people = List.copyOf(people);
        groups = List.copyOf(groups);
    }
}

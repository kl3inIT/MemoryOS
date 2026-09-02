package io.memoryos.identity;

import org.springframework.util.Assert;

public record ExternalIdentity(String issuer, String subject) {

    public ExternalIdentity {
        Assert.hasText(issuer, "issuer must not be blank");
        Assert.hasText(subject, "subject must not be blank");
    }
}

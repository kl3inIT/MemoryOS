package io.memoryos.iam.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public final class ExternalIdentityBindingId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "issuer", nullable = false, columnDefinition = "text")
    private String issuer;

    @Column(name = "subject", nullable = false, columnDefinition = "text")
    private String subject;

    protected ExternalIdentityBindingId() {
    }

    public ExternalIdentityBindingId(String issuer, String subject) {
        this.issuer = requireText(issuer, "issuer");
        this.subject = requireText(subject, "subject");
    }

    public String issuer() {
        return issuer;
    }

    public String subject() {
        return subject;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        return candidate instanceof ExternalIdentityBindingId other
                && issuer.equals(other.issuer)
                && subject.equals(other.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issuer, subject);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

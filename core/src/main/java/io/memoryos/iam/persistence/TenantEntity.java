package io.memoryos.iam.persistence;

import io.memoryos.iam.TenantStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false, length = 63)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TenantStatus status;

    @Column(name = "bootstrap_reference", nullable = false, length = 200)
    private String bootstrapReference;

    @Column(name = "deployment_slot", nullable = false)
    private short deploymentSlot;

    @Column(name = "authorization_version", nullable = false, insertable = false, updatable = false)
    private long authorizationVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TenantEntity() {
    }

    public TenantEntity(
            UUID id,
            String slug,
            String displayName,
            String bootstrapReference
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.slug = requireText(slug, "slug");
        this.displayName = requireText(displayName, "displayName");
        this.bootstrapReference = requireText(bootstrapReference, "bootstrapReference");
        this.status = TenantStatus.ACTIVE;
        this.deploymentSlot = 1;
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getBootstrapReference() {
        return bootstrapReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

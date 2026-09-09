package io.memoryos.iam.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "tenant_bootstrap_state")
public class TenantBootstrapStateEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private short id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private @Nullable TenantEntity tenant;

    protected TenantBootstrapStateEntity() {
    }

    public short getId() {
        return id;
    }

    public @Nullable TenantEntity getTenant() {
        return tenant;
    }

    public void publish(TenantEntity tenant) {
        if (this.tenant != null && !this.tenant.getId().equals(tenant.getId())) {
            throw new IllegalStateException("initial Tenant is already published");
        }
        this.tenant = tenant;
    }
}

package io.memoryos.ai.persistence;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ocr.OcrProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "ocr_connection")
public class OcrConnectionEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private OcrProvider provider;
    @Column(nullable = false, length = 2048) private String endpoint = "";
    @Column(columnDefinition = "text") private @Nullable String credential;
    @Enumerated(EnumType.STRING) @Column(name = "data_boundary", nullable = false) private DataBoundary dataBoundary;
    @Column(nullable = false) private boolean active;
    @Version private @Nullable Long revision;

    protected OcrConnectionEntity() {}
    public OcrConnectionEntity(UUID tenant, OcrProvider provider) {
        id = UUID.randomUUID(); tenantId = tenant; this.provider = provider; dataBoundary = provider.dataBoundary();
    }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public OcrProvider provider() { return provider; }
    public String endpoint() { return endpoint; }
    public @Nullable String credential() { return credential; }
    public DataBoundary dataBoundary() { return dataBoundary; }
    public boolean active() { return active; }
    public long revision() { return revision == null ? 0 : revision; }
    public void configure(String endpoint, @Nullable String credential, DataBoundary dataBoundary) {
        this.endpoint = endpoint; this.credential = credential; this.dataBoundary = dataBoundary;
    }
    public void select(boolean active) { this.active = active; }
}

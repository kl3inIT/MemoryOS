package io.memoryos.ai.persistence;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.systemone.SystemOneProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "system_one_connection")
public class SystemOneConnectionEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private SystemOneProvider provider;
    @Column(nullable = false, length = 80) private String name = "";
    @Column(nullable = false, length = 2048) private String endpoint = "";
    @Column(nullable = false, length = 200) private String model = "";
    @Column(columnDefinition = "text") private @Nullable String credential;
    @Enumerated(EnumType.STRING) @Column(name = "data_boundary", nullable = false) private DataBoundary dataBoundary = DataBoundary.EXTERNAL;
    @Column(name = "input_price", precision = 12, scale = 6) private @Nullable BigDecimal inputPrice;
    @Version private @Nullable Long revision;

    protected SystemOneConnectionEntity() {}
    public SystemOneConnectionEntity(UUID tenant, SystemOneProvider provider) {
        id = UUID.randomUUID(); tenantId = tenant; this.provider = provider;
    }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public SystemOneProvider provider() { return provider; }
    public String name() { return name; }
    public String endpoint() { return endpoint; }
    public String model() { return model; }
    public @Nullable String credential() { return credential; }
    public DataBoundary dataBoundary() { return dataBoundary; }
    public @Nullable Double inputPrice() { return inputPrice == null ? null : inputPrice.doubleValue(); }
    public long revision() { return revision == null ? 0 : revision; }
    public void configure(String name, String endpoint, String model, @Nullable String credential,
                          DataBoundary dataBoundary, @Nullable Double inputPrice) {
        this.name = name; this.endpoint = endpoint; this.model = model; this.credential = credential;
        this.dataBoundary = dataBoundary;
        this.inputPrice = inputPrice == null ? null : BigDecimal.valueOf(inputPrice);
    }
}

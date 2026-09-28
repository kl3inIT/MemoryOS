package io.memoryos.voice.persistence;

import io.memoryos.voice.VoiceProvider;
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
@Table(name = "chat_voice_connection")
public class VoiceConnectionEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private VoiceProvider provider;
    @Column(nullable = false, length = 2048) private String endpoint = "";
    @Column(columnDefinition = "text") private @Nullable String credential;
    @Column(name = "stt_model", nullable = false, length = 200) private String sttModel = "";
    @Column(name = "tts_model", nullable = false, length = 200) private String ttsModel = "";
    @Column(name = "tts_voice", nullable = false, length = 200) private String ttsVoice = "";
    @Column(name = "stt_active", nullable = false) private boolean sttActive;
    @Column(name = "tts_active", nullable = false) private boolean ttsActive;
    @Version private @Nullable Long revision;

    protected VoiceConnectionEntity() {}
    public VoiceConnectionEntity(UUID tenant, VoiceProvider provider) {
        id = UUID.randomUUID(); tenantId = tenant; this.provider = provider;
    }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public VoiceProvider provider() { return provider; }
    public String endpoint() { return endpoint; }
    public @Nullable String credential() { return credential; }
    public String sttModel() { return sttModel; }
    public String ttsModel() { return ttsModel; }
    public String ttsVoice() { return ttsVoice; }
    public boolean sttActive() { return sttActive; }
    public boolean ttsActive() { return ttsActive; }
    public long revision() { return revision == null ? 0 : revision; }
    public void configure(String endpoint, String sttModel, String ttsModel, String ttsVoice, @Nullable String credential) {
        this.endpoint = endpoint; this.sttModel = sttModel; this.ttsModel = ttsModel; this.ttsVoice = ttsVoice;
        this.credential = credential;
    }
    public void useTtsModel(String model) { ttsModel = model; }
    public void selectStt(boolean active) { sttActive = active; }
    public void selectTts(boolean active) { ttsActive = active; }
}

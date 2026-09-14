package io.memoryos.api.chat.contract;

import io.memoryos.iam.group.GroupIdentity;
import io.memoryos.iam.group.GroupIdentityPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatGroupPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChatGroupPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED) int page,
        @Schema(minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED) int size,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED) long totalItems,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED) long totalPages
) {
    public ChatGroupPageResponse { items = List.copyOf(items); }

    @Schema(name = "ChatGroupOption", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String systemKey
    ) {
        static Item from(GroupIdentity value) {
            return new Item(value.id().value(), value.name(),
                    value.systemKey() == null ? null : value.systemKey().name());
        }
    }
    public static ChatGroupPageResponse from(GroupIdentityPage page) {
        return new ChatGroupPageResponse(page.items().stream().map(Item::from).toList(),
                page.page(), page.size(), page.totalItems(), page.totalPages());
    }
}

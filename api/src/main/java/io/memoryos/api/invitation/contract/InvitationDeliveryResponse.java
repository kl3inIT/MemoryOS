package io.memoryos.api.invitation.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "InvitationDelivery")
public enum InvitationDeliveryResponse {
    ACTIVATION_EMAIL_SENT,
    EXISTING_ACCOUNT,
    RECOVERY_LINK_ONLY
}

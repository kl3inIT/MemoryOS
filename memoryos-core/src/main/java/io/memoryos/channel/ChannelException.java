package io.memoryos.channel;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** Expected failures of bot administration, matched by code. */
public final class ChannelException extends BusinessException {
    private ChannelException(String code, FailureCategory category, String safeMessage, String diagnosticMessage) {
        super(code, category, safeMessage, diagnosticMessage);
    }

    public static ChannelException notConfigured() {
        return new ChannelException("CHANNEL_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "Chat bots are not configured on this deployment. Contact the deployment owner.",
                "Channel credential encryption key is missing or unusable");
    }

    public static ChannelException notFound() {
        return new ChannelException("CHANNEL_NOT_FOUND", FailureCategory.NOT_FOUND,
                "The bot was not found.", "Channel connection is missing from the Tenant");
    }

    public static ChannelException changed() {
        return new ChannelException("CHANNEL_CHANGED", FailureCategory.CONFLICT,
                "The bot was changed by someone else. Reload it and try again.", "Channel connection revision moved on");
    }

    public static ChannelException invalid(String safeMessage) {
        return new ChannelException("CHANNEL_INVALID", FailureCategory.VALIDATION, safeMessage, "Channel connection input is invalid");
    }

    public static ChannelException rejected() {
        return new ChannelException("CHANNEL_APP_REJECTED", FailureCategory.VALIDATION,
                "The platform rejected the App ID or the App Secret, or the app has no bot. Copy both from the app's "
                        + "credentials page and turn on its bot.", "Channel app was rejected by the platform");
    }

    public static ChannelException providerUnavailable() {
        return new ChannelException("CHANNEL_PROVIDER_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "The platform did not answer. Try again in a moment.", "Channel platform did not answer");
    }

    public static ChannelException appInUse() {
        return new ChannelException("CHANNEL_APP_IN_USE", FailureCategory.CONFLICT,
                "Another bot already uses this app. Each bot needs an app of its own.", "Channel app is already connected");
    }
}

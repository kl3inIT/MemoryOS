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

    public static ChannelException groupNotFound() {
        return new ChannelException("CHANNEL_GROUP_NOT_FOUND", FailureCategory.NOT_FOUND,
                "The group chat was not found.", "Channel group is missing from the bot");
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

    public static ChannelException webUrlMissing() {
        return new ChannelException("CHANNEL_WEB_URL_MISSING", FailureCategory.VALIDATION,
                "This deployment has no public web address, so the platform cannot push messages to it. Choose polling.",
                "memoryos.channel.web-url is empty");
    }

    /** The platform could not call the bot's webhook; {@code outcome} is the platform's typed reason, never its text. */
    public static ChannelException webhookUnreachable(String outcome) {
        return new ChannelException("CHANNEL_WEBHOOK_UNREACHABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "The platform could not reach this deployment's public address. Check the address or choose polling.",
                "Channel webhook check failed: " + outcome);
    }

    public static ChannelException providerQuota() {
        return new ChannelException("CHANNEL_PROVIDER_QUOTA", FailureCategory.SERVICE_UNAVAILABLE,
                "The bot has used up its plan's messages on the platform.", "Channel platform quota exceeded");
    }

    public static ChannelException assistantNotEditable() {
        return new ChannelException("CHANNEL_ASSISTANT_NOT_EDITABLE", FailureCategory.NOT_PERMITTED,
                "Only someone who can edit the assistant can connect a bot to it or remove one from it.",
                "Channel binding needs edit rights on the assistant");
    }

    /** WhatsApp logged the bot's device out: it was removed on the phone, or the number was banned for good. */
    public static ChannelException whatsappLoggedOut() {
        return new ChannelException("CHANNEL_WHATSAPP_LOGGED_OUT", FailureCategory.SERVICE_UNAVAILABLE,
                "WhatsApp logged the bot out. Link it again with the bot's phone.", "WhatsApp logged the device out");
    }

    /** WhatsApp banned the bot's number for a while. */
    public static ChannelException whatsappBanned() {
        return new ChannelException("CHANNEL_WHATSAPP_BANNED", FailureCategory.SERVICE_UNAVAILABLE,
                "WhatsApp has temporarily banned the bot's number.", "WhatsApp banned the number temporarily");
    }

    /** Another client opened the bot's session, so WhatsApp closed this one. */
    public static ChannelException whatsappReplaced() {
        return new ChannelException("CHANNEL_WHATSAPP_REPLACED", FailureCategory.CONFLICT,
                "Another process opened the bot's WhatsApp session. Link it again if that was not expected.",
                "WhatsApp session was opened elsewhere");
    }

    /** A number other than the bot's scanned its code; it was logged out again. */
    public static ChannelException whatsappWrongNumber() {
        return new ChannelException("CHANNEL_WHATSAPP_WRONG_NUMBER", FailureCategory.VALIDATION,
                "The code was scanned by another number. Scan it with the bot's own phone.",
                "WhatsApp pairing came from another number");
    }

    public static ChannelException appInUse() {
        return new ChannelException("CHANNEL_APP_IN_USE", FailureCategory.CONFLICT,
                "Another bot already uses this app. Each bot needs an app of its own.", "Channel app is already connected");
    }
}

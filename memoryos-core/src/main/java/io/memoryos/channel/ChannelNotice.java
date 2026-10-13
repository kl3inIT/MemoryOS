package io.memoryos.channel;

/**
 * What a bot says in its own voice, in the language of its connection. No notice carries a provider message, a
 * failure text or anything of the question.
 */
enum ChannelNotice {
    OUTSIDER("Trợ lý này chỉ phục vụ thành viên của tổ chức.",
            "This assistant serves members of the organisation only."),
    EXTERNAL_GROUP("Trợ lý không trả lời trong nhóm có người ngoài tổ chức. Hãy nhắn riêng cho trợ lý.",
            "The assistant does not answer in a group with people outside the organisation. Send it a direct message."),
    TEXT_ONLY("Hiện trợ lý chỉ đọc được tin nhắn văn bản.", "The assistant reads text messages only for now."),
    NEW_CONVERSATION("Đã bắt đầu cuộc trò chuyện mới.", "A new conversation has started."),
    BUSY("Trợ lý đang bận. Hãy hỏi lại sau ít phút.", "The assistant is busy. Ask again in a moment."),
    LIMIT("Đã hết hạn mức sử dụng AI. Hãy liên hệ quản trị viên.", "The AI usage limit is reached. Contact an administrator."),
    FAILED("Trợ lý chưa trả lời được câu này. Hãy thử lại.", "The assistant could not answer this. Try again."),
    GROUP_FOOTER("Trả lời vào tin này để hỏi tiếp. Nhắn riêng cho trợ lý để hỏi theo tài liệu của riêng bạn.",
            "Reply to this message to continue. Send the assistant a direct message to ask from your own documents."),
    UNMAPPED_FOOTER("Đăng nhập MemoryOS bằng email công việc để trợ lý dùng được tài liệu của riêng bạn.",
            "Sign in to MemoryOS with your work e-mail so the assistant can use your own documents.");

    private final String vi;
    private final String en;

    ChannelNotice(String vi, String en) {
        this.vi = vi;
        this.en = en;
    }

    String in(String language) {
        return "en".equals(language) ? en : vi;
    }
}

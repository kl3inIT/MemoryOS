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
    GROUP_NOT_ALLOWED("Nhóm này chưa được quản trị viên cho phép dùng trợ lý.",
            "An administrator has not allowed the assistant in this group yet."),
    LINK_REQUIRED("Để hỏi trợ lý, hãy liên kết tài khoản MemoryOS: mở Cài đặt › Chat trên MemoryOS, lấy mã liên kết rồi nhắn riêng mã đó cho trợ lý.",
            "To ask the assistant, link your MemoryOS account: open Settings › Chat in MemoryOS, get a linking code and send it to the assistant in a direct message."),
    LINK_IN_DIRECT("Nhắn riêng cho trợ lý để liên kết tài khoản.", "Message the assistant directly to link your account."),
    LINKED("Đã liên kết tài khoản MemoryOS của bạn. Giờ bạn có thể hỏi trợ lý.",
            "Your MemoryOS account is linked. You can ask the assistant now."),
    LINK_ALREADY("Tài khoản này đã được liên kết với một tài khoản MemoryOS. Muốn liên kết tài khoản khác, hãy bỏ liên kết trong Cài đặt › Chat trên MemoryOS trước.",
            "This account is already linked to a MemoryOS account. To link another one, unlink it in Settings › Chat in MemoryOS first."),
    LINK_CODE_INVALID("Mã liên kết không đúng hoặc đã hết hạn. Hãy lấy mã mới trong Cài đặt › Chat trên MemoryOS.",
            "The linking code is wrong or has expired. Get a new one in Settings › Chat in MemoryOS."),
    TEXT_ONLY("Hiện trợ lý chỉ đọc được tin nhắn văn bản.", "The assistant reads text messages only for now."),
    NEW_CONVERSATION("Đã bắt đầu cuộc trò chuyện mới.", "A new conversation has started."),
    WRITING("Đang trả lời…", "Answering…"),
    BUSY("Trợ lý đang bận. Hãy hỏi lại sau ít phút.", "The assistant is busy. Ask again in a moment."),
    RATE("Bạn đang gửi quá nhanh. Hãy đợi một phút rồi hỏi tiếp.", "You are sending messages too fast. Wait a minute, then ask again."),
    LIMIT("Đã hết hạn mức sử dụng AI. Hãy liên hệ quản trị viên.", "The AI usage limit is reached. Contact an administrator."),
    FAILED("Trợ lý chưa trả lời được câu này. Hãy thử lại.", "The assistant could not answer this. Try again."),
    INCOMPLETE("Câu trả lời chưa gửi hết. Hãy hỏi lại.", "The answer was not sent in full. Ask again."),
    CONTINUE_ON_WEB("Tiếp tục trên MemoryOS", "Continue in MemoryOS"),
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

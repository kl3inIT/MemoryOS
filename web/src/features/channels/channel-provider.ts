import type { ChannelResponse } from "@/lib/hey-api/types.gen";

type Provider = ChannelResponse["provider"];

/** A platform's own words, each a catalog key the caller translates. */
type Words = {
  name: string;
  bot: string;
  group: string;
  failed: string;
  connecting: string;
  answering: string;
};

const WORDS: Readonly<Record<Provider, Words>> = {
  LARK: {
    name: "Lark",
    bot: "Bot Lark",
    group: "Nhóm Lark",
    failed: "Không kết nối được tới Lark",
    connecting: "Đang kết nối tới Lark…",
    answering: "Đang trả lời trên Lark",
  },
  ZALO_BOT: {
    name: "Zalo",
    bot: "Bot Zalo",
    group: "Nhóm Zalo",
    failed: "Không kết nối được tới Zalo",
    connecting: "Đang kết nối tới Zalo…",
    answering: "Đang trả lời trên Zalo",
  },
  WHATSAPP: {
    name: "WhatsApp",
    bot: "Bot WhatsApp",
    group: "Nhóm WhatsApp",
    failed: "Không kết nối được tới WhatsApp",
    connecting: "Đang kết nối tới WhatsApp…",
    answering: "Đang trả lời trên WhatsApp",
  },
};

export function providerWords(provider: Provider): Words {
  return WORDS[provider];
}

/** A platform without organisations, whose members link their own account with a code (MEM-263). */
export function linksAccounts(provider: Provider) {
  return provider === "ZALO_BOT" || provider === "WHATSAPP";
}

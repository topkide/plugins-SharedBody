package com.dotorimaru.sharedbody.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/** '&' 컬러코드 → 레거시 색상 문자열 헬퍼 + 공용 프리픽스. (볼드 &l 미사용) */
public final class Msg {

    public static final String PREFIX = "&8【&6공유&8】&r ";

    private Msg() {}

    public static String c(String legacy) {
        return ChatColor.translateAlternateColorCodes('&', legacy);
    }

    public static void send(CommandSender to, String legacy) {
        to.sendMessage(c(legacy));
    }

    /** 프리픽스를 붙여 전송. */
    public static void tell(CommandSender to, String legacy) {
        to.sendMessage(c(PREFIX + legacy));
    }
}

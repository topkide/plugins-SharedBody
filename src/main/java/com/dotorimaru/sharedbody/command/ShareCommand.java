package com.dotorimaru.sharedbody.command;

import com.dotorimaru.sharedbody.sync.SyncService;
import com.dotorimaru.sharedbody.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ShareCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "on", "off", "상태", "초기화", "리더설정", "리더해제", "제외설정", "제외취소", "제외인원", "공유인원");

    private final SyncService sync;

    public ShareCommand(SyncService sync) {
        this.sync = sync;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("sharedbody.admin")) {
            Msg.tell(sender, "&c권한이 없습니다.");
            return true;
        }
        if (args.length == 0) { help(sender); return true; }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on" -> {
                sync.setEnabled(true);
                Msg.tell(sender, "&a공유 시스템을 &f활성화&a했습니다.");
            }
            case "off" -> {
                sync.setEnabled(false);
                Msg.tell(sender, "&c공유 시스템을 &f비활성화&c했습니다.");
            }
            case "상태", "status" -> {
                String leader = sync.getLeaderName();
                Msg.tell(sender, "&f상태: " + (sync.isEnabled() ? "&aON" : "&cOFF")
                        + " &8| &f공유중 &e" + sync.sharingPlayers().size() + "&f명 &8| &f제외 &e"
                        + sync.excludedNames().size() + "&f명 &8| &f리더 "
                        + (leader != null ? "&e" + leader : "&7미설정"));
            }
            case "초기화", "reset" -> {
                sync.reset();
                Msg.tell(sender, "&a공유 상태를 초기화했습니다. &7(인벤/엔더/레벨/허기/체력 리셋)");
            }
            case "리더설정" -> {
                if (args.length < 2) { Msg.tell(sender, "&c/공유 리더설정 <닉네임>"); return true; }
                String name = args[1];
                sync.setLeader(name);
                Msg.tell(sender, "&e" + name + " &f님을 리더로 설정했습니다. &7(사망 시 이 위치에 아이템 드랍)");
            }
            case "리더해제" -> {
                if (sync.getLeaderName() == null) {
                    Msg.tell(sender, "&f설정된 리더가 없습니다.");
                } else {
                    sync.clearLeader();
                    Msg.tell(sender, "&a리더를 해제했습니다. &7(사망 시 사망 유발 플레이어 위치에 드랍)");
                }
            }
            case "제외설정" -> {
                if (args.length < 2) { Msg.tell(sender, "&c/공유 제외설정 <닉네임>"); return true; }
                String name = args[1];
                if (sync.exclude(name)) {
                    Msg.tell(sender, "&e" + name + " &f님을 공유에서 &c제외&f했습니다.");
                } else {
                    Msg.tell(sender, "&e" + name + " &f님은 이미 제외 상태입니다.");
                }
            }
            case "제외취소" -> {
                if (args.length < 2) { Msg.tell(sender, "&c/공유 제외취소 <닉네임>"); return true; }
                String name = args[1];
                if (sync.include(name)) {
                    Msg.tell(sender, "&e" + name + " &f님의 제외를 &a해제&f했습니다. &7(다시 모든 값 공유)");
                } else {
                    Msg.tell(sender, "&e" + name + " &f님은 제외 목록에 없습니다.");
                }
            }
            case "제외인원" -> {
                List<String> ex = sync.excludedNames();
                if (ex.isEmpty()) { Msg.tell(sender, "&f제외된 인원이 없습니다."); return true; }
                Msg.tell(sender, "&f제외 인원 &7(" + ex.size() + "명)&f: &e" + String.join("&f, &e", ex));
            }
            case "공유인원" -> {
                List<Player> sharing = sync.sharingPlayers();
                if (sharing.isEmpty()) { Msg.tell(sender, "&f공유 중인 인원이 없습니다."); return true; }
                List<String> names = new ArrayList<>();
                for (Player p : sharing) names.add(p.getName());
                Msg.tell(sender, "&f공유 인원 &7(" + names.size() + "명)&f: &a" + String.join("&f, &a", names));
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        Msg.send(s, "&8&m----------------&r &6공유 &8&m----------------");
        Msg.send(s, "&e/공유 on&7, &e/공유 off &8- &f시스템 켜기/끄기");
        Msg.send(s, "&e/공유 상태 &8- &f현재 상태 확인");
        Msg.send(s, "&e/공유 초기화 &8- &f공유 값 전부 리셋");
        Msg.send(s, "&e/공유 리더설정 <닉네임> &8- &f사망 시 아이템 드랍 기준");
        Msg.send(s, "&e/공유 리더해제 &8- &f리더 설정 해제");
        Msg.send(s, "&e/공유 제외설정 <닉네임> &8- &f해당 인원 공유 제외");
        Msg.send(s, "&e/공유 제외취소 <닉네임> &8- &f제외 해제");
        Msg.send(s, "&e/공유 제외인원 &8- &f제외 목록 확인");
        Msg.send(s, "&e/공유 공유인원 &8- &f공유 중인 인원 확인");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("sharedbody.admin")) return List.of();

        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : SUBS) if (s.toLowerCase(Locale.ROOT).startsWith(pre)) out.add(s);
            return out;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String pre = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (sub.equals("제외설정")) {
                // 온라인 플레이어 중 아직 제외 안 된 인원
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (sync.isExcluded(p.getName())) continue;
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
                }
            } else if (sub.equals("리더설정")) {
                // 온라인 참가자(관전/제외 제외)
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (sync.isExcluded(p.getName())) continue;
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
                }
            } else if (sub.equals("제외취소")) {
                // 현재 제외된 인원
                for (String n : sync.excludedNames()) {
                    if (n.startsWith(pre)) out.add(n);
                }
            }
            return out;
        }
        return List.of();
    }
}

package com.dotorimaru.sharedbody;

import com.dotorimaru.sharedbody.command.ShareCommand;
import com.dotorimaru.sharedbody.sync.SyncService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SharedBodyPlugin extends JavaPlugin {

    private SyncService sync;

    @Override
    public void onEnable() {
        this.sync = new SyncService(this);
        getServer().getPluginManager().registerEvents(sync, this);
        sync.runTaskTimer(this, 1L, 1L); // 매 틱 동기화 (소규모라 부담 없음)

        registerCommand("공유", new ShareCommand(sync));

        getLogger().info("SharedBody 활성화 완료.");
    }

    @Override
    public void onDisable() {
        if (sync != null) sync.save();
        getLogger().info("SharedBody 비활성화.");
    }

    private void registerCommand(String name, ShareCommand exec) {
        PluginCommand c = getCommand(name);
        if (c == null) {
            getLogger().severe("'" + name + "' 명령어 등록 실패 (plugin.yml 확인).");
            return;
        }
        c.setExecutor(exec);
        c.setTabCompleter(exec);
    }
}

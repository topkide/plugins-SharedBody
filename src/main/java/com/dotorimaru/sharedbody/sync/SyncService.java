package com.dotorimaru.sharedbody.sync;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 공유 몸(shared body) 동기화 서비스.
 *
 * 매 틱마다 각 참가자의 실제 값을 공유 상태(SharedState)와 대조한다.
 *  - 공유 상태와 달라진 플레이어가 "변경 주체"가 되어 그 값을 공유 상태에 반영한다.
 *  - 그 뒤 공유 상태를 전원에게 다시 적용한다.
 * 줍기/제작/소비/블록설치/자연회복 등 인벤토리 변경 경로를 개별 이벤트로 잡지 않아도
 * 결과값만으로 자동 동기화된다.
 *
 * 제외(exclude)된 플레이어는 참가자 풀에서 빠져 자신의 인벤/체력을 독립적으로 갖고,
 * 동시 사망 트리거에서도 제외된다.
 */
public class SyncService extends BukkitRunnable implements Listener {

    private final JavaPlugin plugin;
    private final SharedState state = new SharedState();
    private final Set<UUID> synced = new HashSet<>();           // 이미 공유 상태에 맞춰진 참가자
    private final Set<String> excluded = new HashSet<>();       // 제외 인원 (소문자 닉네임)
    private String leaderName = null;                            // 사망 시 아이템 드랍 기준 (소문자 닉네임)
    private boolean enabled = true;
    private boolean groupDying = false;                          // 동시 사망 중복 방지

    private final File dataFile;

    public SyncService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    // ============================================================
    //  메인 루프 (매 틱)
    // ============================================================
    @Override
    public void run() {
        if (!enabled) return;

        List<Player> participants = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isParticipant(p)) participants.add(p);
        }

        // 접속종료/관전/사망/제외로 빠진 인원은 synced 에서 제거 → 재합류 시 공유 상태를 새로 받음
        Set<UUID> current = new HashSet<>();
        for (Player p : participants) current.add(p.getUniqueId());
        synced.retainAll(current);

        if (participants.isEmpty()) return;

        // 최초 1회: 먼저 들어온 사람의 값을 공유 상태의 씨앗으로 삼는다.
        if (!state.initialized) {
            Player seed = participants.get(0);
            captureInventory(seed);
            captureEnder(seed);
            captureXp(seed);
            captureFood(seed);
            state.health = seed.getHealth();
            state.initialized = true;
        }

        // --- 변경 주체 탐지 (이미 synced 된 사람만 주체가 될 수 있음) ---
        boolean invDone = false, enderDone = false, xpDone = false, foodDone = false;
        for (Player p : participants) {
            if (!synced.contains(p.getUniqueId())) continue; // 신규 합류자는 이번 틱에 주체가 되지 않음
            if (!invDone && inventoryDiffers(p))   { captureInventory(p); invDone = true; }
            if (!enderDone && enderDiffers(p))      { captureEnder(p);     enderDone = true; }
            if (!xpDone && xpDiffers(p))            { captureXp(p);        xpDone = true; }
            if (!foodDone && foodDiffers(p))        { captureFood(p);      foodDone = true; }
        }
        adoptHealth(participants);

        // --- 공유 상태를 전원에게 적용 (신규 합류자도 여기서 공유 상태를 받고 synced 등록) ---
        for (Player p : participants) {
            applyTo(p);
            synced.add(p.getUniqueId());
        }
    }

    // ============================================================
    //  이벤트: 치명타 → 전원 동시 사망
    // ============================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!enabled) return;
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isParticipant(p)) return;

        double resulting = p.getHealth() - e.getFinalDamage();
        if (resulting > 0.0) return; // 치명타 아님 → 체력 감소는 매 틱 대조로 전파됨
        if (groupDying) return;

        groupDying = true;

        // 1) 드랍할 아이템 스냅샷 (본체 + 방어구 + 보조손). 엔더상자는 바닐라처럼 유지.
        List<ItemStack> toDrop = new ArrayList<>();
        collectDrop(toDrop, state.storage);
        collectDrop(toDrop, state.armor);
        if (!isEmpty(state.offhand)) toDrop.add(state.offhand.clone());

        // 2) 드랍 위치 = 리더(온라인·참가자). 없거나 유효하지 않으면 사망을 유발한 본인 위치.
        Player leader = resolveLeader();
        Location dropLoc = (leader != null ? leader : p).getLocation();

        // 3) 전원 처치 (아직 살아있는 참가자)
        for (Player q : Bukkit.getOnlinePlayers()) {
            if (q.getUniqueId().equals(p.getUniqueId())) continue;
            if (!isParticipant(q)) continue;
            if (q.isDead()) continue;
            q.setHealth(0.0); // setHealth 는 EntityDamageEvent 를 다시 발생시키지 않음 → 재귀 없음
        }

        // 4) 공유 인벤토리(본체/방어구/보조손) 비우기 → 아이템은 리더 위치로 이동, 엔더상자는 유지
        state.storage = new ItemStack[36];
        state.armor = new ItemStack[4];
        state.offhand = null;
        state.health = maxHealth(p); // 부활 후 재사망 방지를 위해 풀 회복으로 초기화

        // 5) 리더 위치에 실제 드랍 (한 번만)
        World w = dropLoc != null ? dropLoc.getWorld() : null;
        if (w != null) {
            for (ItemStack it : toDrop) w.dropItemNaturally(dropLoc, it);
        }

        Bukkit.getScheduler().runTask(plugin, () -> groupDying = false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!enabled) return;
        Player p = e.getEntity();
        if (!isParticipant(p) && !synced.contains(p.getUniqueId())) return; // 제외 인원은 관여 안 함
        // 아이템은 리더 위치에 이미 한 번만 드랍되므로, 개인별 바닐라 드랍은 막아 복제를 방지한다.
        e.getDrops().clear();
        e.setDroppedExp(0);
        // 부활 시 공유 상태를 다시 받도록 synced 에서 제거 (빈 인벤토리로 공유 상태를 덮어쓰는 사고 방지)
        synced.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isParticipant(p)) {
                applyTo(p);
                synced.add(p.getUniqueId());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        synced.remove(e.getPlayer().getUniqueId());
    }

    // ============================================================
    //  탐지 (differs) / 캡처 (capture)
    // ============================================================
    private boolean inventoryDiffers(Player p) {
        PlayerInventory inv = p.getInventory();
        if (!sameItems(state.storage, inv.getStorageContents())) return true;
        if (!sameItems(state.armor, inv.getArmorContents())) return true;
        return !equalItem(state.offhand, inv.getItemInOffHand());
    }

    private void captureInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        state.storage = normArray(inv.getStorageContents(), 36);
        state.armor = normArray(inv.getArmorContents(), 4);
        state.offhand = norm(inv.getItemInOffHand());
    }

    private boolean enderDiffers(Player p) {
        return !sameItems(state.ender, p.getEnderChest().getContents());
    }

    private void captureEnder(Player p) {
        state.ender = normArray(p.getEnderChest().getContents(), 27);
    }

    private boolean xpDiffers(Player p) {
        return p.getLevel() != state.level || Math.abs(p.getExp() - state.exp) > 1e-4f;
    }

    private void captureXp(Player p) {
        state.level = p.getLevel();
        state.exp = clamp01(p.getExp());
    }

    private boolean foodDiffers(Player p) {
        return p.getFoodLevel() != state.food
                || Math.abs(p.getSaturation() - state.saturation) > 1e-3f
                || Math.abs(p.getExhaustion() - state.exhaustion) > 1e-3f;
    }

    private void captureFood(Player p) {
        state.food = p.getFoodLevel();
        state.saturation = p.getSaturation();
        state.exhaustion = p.getExhaustion();
    }

    /** 체력은 "데미지 우선" 규칙: 누구든 깎이면 그 값으로, 아니면 회복분 반영. */
    private void adoptHealth(List<Player> participants) {
        double lowest = state.health;
        boolean dmg = false;
        for (Player p : participants) {
            if (!synced.contains(p.getUniqueId())) continue;
            double h = p.getHealth();
            if (h < state.health - 1e-4) {
                dmg = true;
                if (h < lowest) lowest = h;
            }
        }
        if (dmg) {
            state.health = Math.max(lowest, 0.0);
            return;
        }
        double highest = state.health;
        boolean heal = false;
        for (Player p : participants) {
            if (!synced.contains(p.getUniqueId())) continue;
            double h = p.getHealth();
            if (h > state.health + 1e-4) {
                heal = true;
                if (h > highest) highest = h;
            }
        }
        if (heal) state.health = highest;
    }

    // ============================================================
    //  적용 (apply) — 값이 다를 때만 세팅해 패킷/렉 최소화
    // ============================================================
    private void applyTo(Player p) {
        PlayerInventory inv = p.getInventory();

        if (!sameItems(state.storage, inv.getStorageContents())) {
            inv.setStorageContents(cloneArray(state.storage));
        }
        if (!sameItems(state.armor, inv.getArmorContents())) {
            inv.setArmorContents(cloneArray(state.armor));
        }
        if (!equalItem(state.offhand, inv.getItemInOffHand())) {
            inv.setItemInOffHand(state.offhand == null ? new ItemStack(Material.AIR) : state.offhand.clone());
        }
        if (!sameItems(state.ender, p.getEnderChest().getContents())) {
            p.getEnderChest().setContents(cloneArray(state.ender));
        }

        if (p.getLevel() != state.level) p.setLevel(state.level);
        if (Math.abs(p.getExp() - state.exp) > 1e-4f) p.setExp(clamp01(state.exp));

        if (p.getFoodLevel() != state.food) p.setFoodLevel(state.food);
        if (Math.abs(p.getSaturation() - state.saturation) > 1e-3f) p.setSaturation(state.saturation);
        if (Math.abs(p.getExhaustion() - state.exhaustion) > 1e-3f) p.setExhaustion(state.exhaustion);

        if (state.health > 0.0) {
            double target = Math.min(state.health, maxHealth(p));
            if (target > 0.0 && Math.abs(p.getHealth() - target) > 1e-4) {
                p.setHealth(target);
            }
        }
    }

    // ============================================================
    //  참가자 판정 / 제외 시스템
    // ============================================================
    private boolean isParticipant(Player p) {
        return p != null
                && p.isOnline()
                && !p.isDead()
                && p.getGameMode() != GameMode.SPECTATOR
                && !isExcluded(p.getName());
    }

    public boolean isExcluded(String name) {
        return name != null && excluded.contains(name.toLowerCase(Locale.ROOT));
    }

    /** @return 새로 제외되었으면 true, 이미 제외 상태면 false */
    public boolean exclude(String name) {
        boolean added = excluded.add(name.toLowerCase(Locale.ROOT));
        if (added) {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null) synced.remove(p.getUniqueId()); // 즉시 풀에서 분리 (현재 아이템은 그대로 유지)
            save();
        }
        return added;
    }

    /** @return 실제로 제외 해제되었으면 true, 원래 제외 아니었으면 false */
    public boolean include(String name) {
        boolean removed = excluded.remove(name.toLowerCase(Locale.ROOT));
        if (removed) save();
        return removed;
    }

    /** 제외 인원 닉네임 목록 (소문자, 정렬됨). */
    public List<String> excludedNames() {
        return new ArrayList<>(new TreeSet<>(excluded));
    }

    /** 현재 공유 중인 온라인 인원. */
    public List<Player> sharingPlayers() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (isExcluded(p.getName())) continue;
            out.add(p);
        }
        return out;
    }

    // ============================================================
    //  리더 시스템 (사망 시 아이템 드랍 기준)
    // ============================================================
    /** 사망 시 공유 인벤토리가 이 플레이어 위치에 드랍된다. */
    public void setLeader(String name) {
        this.leaderName = name == null ? null : name.toLowerCase(Locale.ROOT);
        save();
    }

    public void clearLeader() {
        this.leaderName = null;
        save();
    }

    /** 설정된 리더 닉네임(소문자) 또는 null. */
    public String getLeaderName() {
        return leaderName;
    }

    /** 리더가 온라인·참가자면 그 Player, 아니면 null. */
    private Player resolveLeader() {
        if (leaderName == null) return null;
        Player p = Bukkit.getPlayerExact(leaderName);
        if (p == null || !isParticipant(p)) return null;
        return p;
    }

    /** 비어있지 않은 아이템만 복제해 리스트에 담는다. */
    private static void collectDrop(List<ItemStack> out, ItemStack[] arr) {
        if (arr == null) return;
        for (ItemStack it : arr) {
            if (!isEmpty(it)) out.add(it.clone());
        }
    }

    // ============================================================
    //  외부 제어
    // ============================================================
    public void setEnabled(boolean value) {
        this.enabled = value;
        if (!value) synced.clear();
        save();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reset() {
        state.blank();
        synced.clear(); // 전원 다음 틱에 빈 공유 상태를 다시 받음
    }

    // ============================================================
    //  영속화 (data.yml)
    // ============================================================
    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        this.enabled = y.getBoolean("enabled", true);
        String lead = y.getString("leader", null);
        this.leaderName = (lead == null || lead.isBlank()) ? null : lead.toLowerCase(Locale.ROOT);
        excluded.clear();
        for (String n : y.getStringList("excluded")) {
            if (n != null && !n.isBlank()) excluded.add(n.toLowerCase(Locale.ROOT));
        }
    }

    public void save() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("enabled", enabled);
            y.set("leader", leaderName);
            y.set("excluded", new ArrayList<>(new TreeSet<>(excluded)));
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("data.yml 저장 실패: " + ex.getMessage());
        }
    }

    // ============================================================
    //  유틸
    // ============================================================
    @SuppressWarnings("deprecation")
    private double maxHealth(Player p) {
        return p.getMaxHealth();
    }

    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType() == Material.AIR || it.getAmount() <= 0;
    }

    private static boolean equalItem(ItemStack a, ItemStack b) {
        boolean ea = isEmpty(a), eb = isEmpty(b);
        if (ea && eb) return true;
        if (ea || eb) return false;
        return a.equals(b);
    }

    private static boolean sameItems(ItemStack[] a, ItemStack[] b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            ItemStack x = i < a.length ? a[i] : null;
            ItemStack y = i < b.length ? b[i] : null;
            if (!equalItem(x, y)) return false;
        }
        return true;
    }

    private static ItemStack norm(ItemStack it) {
        return isEmpty(it) ? null : it.clone();
    }

    private static ItemStack[] normArray(ItemStack[] src, int len) {
        ItemStack[] out = new ItemStack[len];
        if (src != null) {
            for (int i = 0; i < len && i < src.length; i++) out[i] = norm(src[i]);
        }
        return out;
    }

    private static ItemStack[] cloneArray(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 0.9999f) return 0.9999f;
        return v;
    }
}

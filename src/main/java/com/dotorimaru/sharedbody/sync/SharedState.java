package com.dotorimaru.sharedbody.sync;

import org.bukkit.inventory.ItemStack;

/**
 * 모든 참가자가 공유하는 단일 상태(source of truth).
 * 빈 슬롯은 항상 null 로 정규화해서 보관한다.
 */
public class SharedState {

    public ItemStack[] storage = new ItemStack[36]; // 인벤토리 본체(0~35)
    public ItemStack[] armor   = new ItemStack[4];  // 방어구
    public ItemStack   offhand = null;              // 보조 손
    public ItemStack[] ender   = new ItemStack[27]; // 엔더 상자

    public int   level = 0;
    public float exp   = 0f;   // 0.0 ~ 1.0 (레벨바 진행도)

    public int   food       = 20;
    public float saturation = 5f;
    public float exhaustion = 0f;

    public double health = 20.0;

    public boolean initialized = false;

    /** 초기화 명령용: 완전히 비어있는 상태로 만든다. */
    public void blank() {
        storage = new ItemStack[36];
        armor   = new ItemStack[4];
        offhand = null;
        ender   = new ItemStack[27];
        level = 0;
        exp = 0f;
        food = 20;
        saturation = 5f;
        exhaustion = 0f;
        health = 20.0;
        initialized = true;
    }
}

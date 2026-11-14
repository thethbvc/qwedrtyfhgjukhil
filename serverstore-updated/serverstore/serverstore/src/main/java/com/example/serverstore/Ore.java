package com.example.serverstore;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Currency, cheapest to most expensive. value = rough worth (only used for sorting). */
public enum Ore {
    COPPER("Copper", Items.COPPER_INGOT, 1),
    IRON("Iron", Items.IRON_INGOT, 2),
    GOLD("Gold", Items.GOLD_INGOT, 8),
    DIAMOND("Diamond", Items.DIAMOND, 32),
    NETHERITE("Netherite", Items.NETHERITE_INGOT, 256);

    public final String label;
    public final Item item;
    public final int value;

    Ore(String label, Item item, int value) { this.label = label; this.item = item; this.value = value; }

    public static Ore parse(String s) {
        try { return valueOf(s.toUpperCase()); } catch (Exception e) { return IRON; }
    }
}

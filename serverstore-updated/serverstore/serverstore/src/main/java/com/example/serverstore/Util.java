package com.example.serverstore;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Util {
    private static final Map<String, Item> ITEMS = new HashMap<>();

    public static Item item(String id) {
        if (ITEMS.isEmpty()) {
            for (Item i : BuiltInRegistries.ITEM) ITEMS.put(BuiltInRegistries.ITEM.getKey(i).toString(), i);
        }
        return ITEMS.getOrDefault(id, Items.AIR);
    }

    public static String id(Item i) { return BuiltInRegistries.ITEM.getKey(i).toString(); }

    public static Component text(String s, ChatFormatting color) {
        return Component.literal(s).withStyle(color).withStyle(st -> st.withItalic(false));
    }

    public static ItemStack icon(Item item, int count, String name, ChatFormatting color, List<String> lore) {
        ItemStack s = new ItemStack(item, count);
        s.set(DataComponents.CUSTOM_NAME, text(name, color));
        if (lore != null) s.set(DataComponents.LORE, loreOf(lore));
        return s;
    }

    public static ItemLore loreOf(List<String> lines) {
        List<Component> c = new ArrayList<>();
        for (String l : lines) c.add(text(l, ChatFormatting.GRAY));
        return new ItemLore(c);
    }

    public static int count(Player p, Item item) {
        int c = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) c += s.getCount();
        }
        return c;
    }

    public static boolean take(Player p, Item item, int amount) {
        if (count(p, item) < amount) return false;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                int t = Math.min(amount, s.getCount());
                s.shrink(t);
                amount -= t;
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return true;
    }

    /**
     * Counts only plain, unmodified stacks of an item (no enchants, custom names, container contents...).
     * Used for the extra-item price so a buyer can never lose a filled shulker box or an enchanted tool.
     */
    public static int countPlain(Player p, Item item) {
        ItemStack plain = new ItemStack(item);
        int c = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, plain)) c += s.getCount();
        }
        return c;
    }

    public static boolean takePlain(Player p, Item item, int amount) {
        if (countPlain(p, item) < amount) return false;
        ItemStack plain = new ItemStack(item);
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, plain)) {
                int t = Math.min(amount, s.getCount());
                s.shrink(t);
                amount -= t;
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return true;
    }

    public static void give(ServerPlayer p, Item item, int count) {
        if (item == Items.AIR) return;
        int max = Math.max(1, new ItemStack(item).getMaxStackSize());
        while (count > 0) {
            int n = Math.min(max, count);
            ItemStack s = new ItemStack(item, n);
            if (!p.getInventory().add(s)) p.drop(s, false);
            count -= n;
        }
    }

    public static void sound(ServerPlayer p, SoundEvent s, float vol, float pitch) {
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), s, SoundSource.PLAYERS, vol, pitch);
    }

    /** Little "ding" for every button click. */
    public static void click(ServerPlayer p) {
        sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5f, 1.8f);
    }

    /** Green = success chime, red = "no" sound. */
    public static void msg(ServerPlayer p, String s, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(s).withStyle(color));
        if (color == ChatFormatting.GREEN) sound(p, SoundEvents.PLAYER_LEVELUP, 0.4f, 1.2f);
        else if (color == ChatFormatting.RED) sound(p, SoundEvents.VILLAGER_NO, 0.6f, 1.0f);
    }

    public static String timeLeft(long endMillis) {
        long s = Math.max(0, (endMillis - System.currentTimeMillis()) / 1000);
        if (s >= 3600) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }
}

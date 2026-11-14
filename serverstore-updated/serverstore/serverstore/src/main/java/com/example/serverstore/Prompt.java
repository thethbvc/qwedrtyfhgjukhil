package com.example.serverstore;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Lets a menu ask the player to type something in chat (used for search and renaming). */
public final class Prompt {
    private static final Map<UUID, Consumer<String>> WAITING = new HashMap<>();

    public static void ask(ServerPlayer p, String question, Consumer<String> callback) {
        WAITING.put(p.getUUID(), callback);
        p.closeContainer();
        Util.msg(p, question + "  (type cancel to abort)", ChatFormatting.AQUA);
    }

    public static boolean isWaiting(ServerPlayer p) { return WAITING.containsKey(p.getUUID()); }

    public static void handle(ServerPlayer p, String text) {
        Consumer<String> c = WAITING.remove(p.getUUID());
        if (c == null) return;
        String t = text.trim();
        if (t.equalsIgnoreCase("cancel")) {
            Util.msg(p, "Cancelled.", ChatFormatting.YELLOW);
            return;
        }
        c.accept(t);
    }
}

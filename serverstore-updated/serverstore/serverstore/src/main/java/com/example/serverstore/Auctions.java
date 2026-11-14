package com.example.serverstore;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Auctions {
    public static MinecraftServer server;
    public static final int MAX_AUCTIONS = 45;

    public static ServerPlayer online(String uuid) {
        return server == null ? null : server.getPlayerList().getPlayer(UUID.fromString(uuid));
    }

    private static String nice(String id) { return id.replace("minecraft:", "").replace('_', ' '); }

    /** Returns true if the auction was created. */
    public static boolean create(ServerPlayer p, String oreName, int start, int minutes) {
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty()) { Util.msg(p, "Hold the item you want to auction.", ChatFormatting.RED); return false; }
        if (hand.isDamaged() || hand.isEnchanted()) {
            Util.msg(p, "Damaged or enchanted items are not supported (their data would be lost).", ChatFormatting.RED);
            return false;
        }
        if (Data.store.auctions.size() >= MAX_AUCTIONS) { Util.msg(p, "The auction house is full.", ChatFormatting.RED); return false; }
        Ore ore = Ore.parse(oreName);
        Data.Auction a = new Data.Auction();
        a.id = Data.store.nextId++;
        a.seller = p.getUUID().toString();
        a.sellerName = p.getName().getString();
        a.item = Util.id(hand.getItem());
        a.count = hand.getCount();
        a.ore = ore.name();
        a.start = start;
        a.end = System.currentTimeMillis() + minutes * 60_000L;
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Data.store.auctions.add(a);
        Data.save();
        Util.msg(p, "Auction created! Starting bid: " + start + " " + ore.label, ChatFormatting.GREEN);
        if (server != null) {
            for (ServerPlayer o : server.getPlayerList().getPlayers()) {
                if (o == p) continue;
                Util.msg(o, "[Auction] " + a.sellerName + " listed " + a.count + "x " + nice(a.item)
                        + " - starting bid " + start + " " + ore.label + "  (/shop > Bids)", ChatFormatting.GOLD);
                Util.click(o);
            }
        }
        return true;
    }

    public static void bid(ServerPlayer p, Data.Auction a, int inc) {
        if (!Data.store.auctions.contains(a)) { Util.msg(p, "That auction has ended.", ChatFormatting.RED); return; }
        String me = p.getUUID().toString();
        if (me.equals(a.seller)) { Util.msg(p, "You can't bid on your own auction.", ChatFormatting.RED); return; }
        if (me.equals(a.bidder)) { Util.msg(p, "You are already the highest bidder.", ChatFormatting.YELLOW); return; }
        Ore ore = Ore.parse(a.ore);
        int amount = a.bid == 0 ? a.start : a.bid + inc;
        if (!Util.take(p, ore.item, amount)) {
            Util.msg(p, "You need " + amount + " " + ore.label + " to bid.", ChatFormatting.RED);
            return;
        }
        if (a.bidder != null) {
            ServerPlayer prev = online(a.bidder);
            if (prev != null) {
                Util.msg(prev, "You were outbid on " + a.count + "x " + nice(a.item) + " by " + p.getName().getString()
                        + " (" + amount + " " + ore.label + "). Your ores were refunded.", ChatFormatting.YELLOW);
                Util.click(prev);
            }
            deliver(a.bidder, Util.id(ore.item), a.bid);   // refund previous bidder
        }
        a.bid = amount;
        a.bidder = me;
        a.bidderName = p.getName().getString();
        if (a.end - System.currentTimeMillis() < 30_000) a.end += 30_000;     // anti-sniping
        Data.save();
        Util.msg(p, "Bid placed: " + amount + " " + ore.label, ChatFormatting.GREEN);
    }

    public static void cancel(ServerPlayer p, Data.Auction a) {
        if (!Data.store.auctions.contains(a)) return;
        if (a.bidder != null) { Util.msg(p, "You can't cancel an auction that has bids.", ChatFormatting.RED); return; }
        Data.store.auctions.remove(a);
        Util.give(p, Util.item(a.item), a.count);
        Data.save();
        Util.msg(p, "Auction cancelled, item returned.", ChatFormatting.YELLOW);
    }

    /** OP only: remove any auction. Item goes back to the seller, bidder is refunded. */
    public static void adminRemove(ServerPlayer p, Data.Auction a) {
        if (!Data.store.auctions.remove(a)) return;
        deliver(a.seller, a.item, a.count);
        if (a.bidder != null) deliver(a.bidder, Util.id(Ore.parse(a.ore).item), a.bid);
        Data.save();
        Util.msg(p, "Auction removed (item returned to the seller).", ChatFormatting.YELLOW);
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        for (Data.Auction a : new ArrayList<>(Data.store.auctions)) {
            if (a.end <= now) finish(a);
        }
    }

    private static void finish(Data.Auction a) {
        Data.store.auctions.remove(a);
        Ore ore = Ore.parse(a.ore);
        if (a.bidder != null) {
            deliver(a.bidder, a.item, a.count);
            deliver(a.seller, Util.id(ore.item), a.bid);
        } else {
            deliver(a.seller, a.item, a.count);
        }
        Data.save();
    }

    /** Give now if online, otherwise hold until they log in. */
    public static void deliver(String uuid, String itemId, int count) {
        deliver(uuid, itemId, count, "the auction house");
    }

    public static void deliver(String uuid, String itemId, int count, String from) {
        ServerPlayer online = online(uuid);
        if (online != null) {
            Util.give(online, Util.item(itemId), count);
            Util.msg(online, "You received " + count + "x " + nice(itemId) + " from " + from + ".", ChatFormatting.GREEN);
        } else {
            Data.store.pending.computeIfAbsent(uuid, k -> new ArrayList<>()).add(new Data.Pending(itemId, count));
        }
    }

    public static void claim(ServerPlayer p) {
        List<Data.Pending> list = Data.store.pending.remove(p.getUUID().toString());
        if (list == null) return;
        for (Data.Pending d : list) {
            Item it = Util.item(d.item);
            if (it != Items.AIR) Util.give(p, it, d.count);
        }
        Util.msg(p, "You received items from the auction house while you were away.", ChatFormatting.GREEN);
        Data.save();
    }
}

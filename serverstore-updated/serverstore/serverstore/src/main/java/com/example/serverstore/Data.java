package com.example.serverstore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Everything saved to disk: config/serverstore/layout.json and store.json */
public final class Data {

    /** How the GUI looks. The owner can change all of this from the in-game Customize menu. */
    public static class Layout {
        public String mainTitle = "Server Menu";
        public String shopTitle = "Shop";
        public String bidsTitle = "Auctions";
        public String filler = "minecraft:gray_stained_glass_pane";
        public String border = "";
        public boolean clickSounds = true;
        public Map<String, String> icons = new HashMap<>();
        public Map<String, String> names = new HashMap<>();
    }

    public static class Auction {
        public int id;
        public String seller, sellerName;
        public String item;
        public int count;
        public String ore;
        public int start;
        public int bid;
        public String bidder, bidderName;
        public long end;
    }

    /** A fixed-price item that a player put up in the shop. */
    public static class Listing {
        public int id;
        public String seller, sellerName;
        public String item;
        public int count;
        public String ore;
        public int price;
        // Optional extra price: the buyer also pays this many of this item (registry id, null = none).
        public String extraItem;
        public int extraCount;
    }

    public static class Pending {
        public String item;
        public int count;
        public Pending() {}
        public Pending(String item, int count) { this.item = item; this.count = count; }
    }

    public static class Store {
        public int nextId = 1;
        public List<Auction> auctions = new ArrayList<>();
        public List<Listing> listings = new ArrayList<>();
        public Map<String, List<Pending>> pending = new HashMap<>();
    }

    public static Layout layout = new Layout();
    public static Store store = new Store();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path dir() {
        Path d = FMLPaths.CONFIGDIR.get().resolve("serverstore");
        try { Files.createDirectories(d); } catch (Exception ignored) {}
        return d;
    }

    public static void load() {
        try {
            Path l = dir().resolve("layout.json");
            if (Files.exists(l)) layout = GSON.fromJson(Files.readString(l), Layout.class);
            if (layout == null) layout = new Layout();
            if (layout.icons == null) layout.icons = new HashMap<>();
            if (layout.names == null) layout.names = new HashMap<>();
            if (layout.border == null) layout.border = "";
            Path s = dir().resolve("store.json");
            if (Files.exists(s)) store = GSON.fromJson(Files.readString(s), Store.class);
            if (store == null) store = new Store();
            if (store.listings == null) store.listings = new ArrayList<>();
            for (Listing listing : store.listings) {
                if (listing.extraItem != null && listing.extraCount <= 0) listing.extraItem = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        saveLayout();
    }

    public static void saveLayout() {
        try { Files.writeString(dir().resolve("layout.json"), GSON.toJson(layout)); } catch (Exception e) { e.printStackTrace(); }
    }

    public static void save() {
        try { Files.writeString(dir().resolve("store.json"), GSON.toJson(store)); } catch (Exception e) { e.printStackTrace(); }
    }
}

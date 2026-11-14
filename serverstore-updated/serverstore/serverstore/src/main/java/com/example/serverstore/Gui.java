package com.example.serverstore;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class Gui {

    public interface Action { void run(ServerPlayer p, ClickType type); }

    /** A chest menu where every click is cancelled, except our button actions. */
    public static class GuiMenu extends ChestMenu {
        private final Map<Integer, Action> actions = new HashMap<>();
        private final int rows;
        private final SimpleContainer box;
        /** 0 = left click, 1 = right click (of the click that is being handled). */
        public int lastButton = 0;
        /** Called after the player moves items in their own inventory (menus that show the held item redraw). */
        public Runnable onInvChange;

        GuiMenu(int id, Inventory inv, int rows, SimpleContainer box) {
            super(rows == 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6, id, inv, box, rows);
            this.rows = rows;
            this.box = box;
        }

        /** Background pane for a slot: the outer ring can use a different "border" item. */
        ItemStack bg(int slot) {
            int row = slot / 9, col = slot % 9;
            boolean edge = row == 0 || row == rows - 1 || col == 0 || col == 8;
            String id = (edge && !Data.layout.border.isEmpty()) ? Data.layout.border : Data.layout.filler;
            Item f = Util.item(id);
            if (f == Items.AIR) f = Items.GRAY_STAINED_GLASS_PANE;
            ItemStack s = new ItemStack(f);
            s.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
            return s;
        }

        void set(int slot, ItemStack s, Action a) {
            box.setItem(slot, s);
            if (a != null) actions.put(slot, a);
            else actions.remove(slot);
        }

        void clear(int slot) {
            box.setItem(slot, bg(slot));
            actions.remove(slot);
        }

        void fillRest() {
            for (int i = 0; i < rows * 9; i++) {
                if (box.getItem(i).isEmpty()) box.setItem(i, bg(i));
            }
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            int guiSlots = rows * 9;
            if (slot >= 0 && slot < guiSlots) {
                // The chest part: nothing can be taken, placed or dragged here. Only our buttons react.
                if (player instanceof ServerPlayer sp && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
                    Action a = actions.get(slot);
                    if (a != null) {
                        lastButton = button;
                        if (Data.layout.clickSounds) Util.click(sp);          // ding!
                        a.run(sp, type);
                    }
                }
            } else if (type != ClickType.QUICK_MOVE && type != ClickType.CLONE) {
                // The player's own inventory (or dropping the cursor item outside the window): normal
                // vanilla behaviour. Shift-click is ignored so nothing can be pushed into the chest part.
                super.clicked(slot, button, type, player);
                if (onInvChange != null && player.containerMenu == this) onInvChange.run();
            }
            if (player.containerMenu == this) this.sendAllDataToRemote();
        }

        /** Double-click "gather all" must never pull items out of the chest part. */
        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
            return slot.container != box && super.canTakeItemForPickAll(stack, slot);
        }

        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player p) { return true; }
    }

    private static void open(ServerPlayer p, String title, int rows, Consumer<GuiMenu> builder) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> {
            GuiMenu m = new GuiMenu(id, inv, rows, new SimpleContainer(rows * 9));
            builder.accept(m);
            m.fillRest();
            return m;
        }, Component.literal(title)));
    }

    // ---------------------------------------------------------------- helpers
    private static boolean isOwner(ServerPlayer p) {
        return ServerStore.isOp(p.createCommandSourceStack());
    }

    private static int delta(GuiMenu m, ClickType t, int small, int big) {
        int b = t == ClickType.QUICK_MOVE ? big : small;
        return m.lastButton == 1 ? -b : b;
    }

    private static String nice(String id) { return id.replace("minecraft:", "").replace('_', ' '); }

    private static List<String> balance(ServerPlayer p) {
        List<String> l = new ArrayList<>();
        for (Ore o : Ore.values()) l.add(o.label + ": " + Util.count(p, o.item));
        return l;
    }

    private static Item iconFor(String key, Item def) {
        String id = Data.layout.icons.get(key);
        if (id == null) return def;
        Item it = Util.item(id);
        return it == Items.AIR ? def : it;
    }

    private static String nameFor(String key, String def) {
        String n = Data.layout.names.get(key);
        return (n == null || n.isEmpty()) ? def : n;
    }

    /** '&6' style color codes typed by the owner become real colors. */
    private static String colorize(String s) { return s.replace('&', '\u00a7'); }

    // ================================================================ main menu
    public static void main(ServerPlayer p) {
        open(p, Data.layout.mainTitle, 3, m -> {
            m.set(4, Util.icon(Items.PAPER, 1, "Your ores", ChatFormatting.WHITE, balance(p)), null);
            m.set(11, Util.icon(iconFor("shop", Items.CHEST), 1, nameFor("shop", "Shop"), ChatFormatting.GREEN,
                    List.of("Buy items other players listed.", "List your own items too.")), (pl, t) -> market(pl));
            m.set(13, Util.icon(iconFor("bids", Items.GOLD_INGOT), 1, nameFor("bids", "Bids"), ChatFormatting.GOLD,
                    List.of("See every open auction.", "Left click an item to bid.")), (pl, t) -> bids(pl, false));
            m.set(15, Util.icon(iconFor("mine", Items.WRITABLE_BOOK), 1, nameFor("mine", "My Auctions"), ChatFormatting.AQUA,
                    List.of("Your listings.", "Create new auctions here.")), (pl, t) -> bids(pl, true));
            if (isOwner(p)) {
                m.set(22, Util.icon(Items.COMMAND_BLOCK, 1, "Owner Menu", ChatFormatting.RED,
                        List.of("Only OPs can see this.")), (pl, t) -> ownerMenu(pl));
            }
        });
    }

    // ================================================================ bids / my auctions
    public static void bids(ServerPlayer p, boolean mine) {
        open(p, mine ? "My Auctions" : Data.layout.bidsTitle, 6, m -> {
            String me = p.getUUID().toString();
            boolean owner = isOwner(p);
            int slot = 0;
            for (Data.Auction a : new ArrayList<>(Data.store.auctions)) {
                if (mine && !me.equals(a.seller)) continue;
                if (slot >= 45) break;
                Item it = Util.item(a.item);
                if (it == Items.AIR) continue;
                Ore ore = Ore.parse(a.ore);
                ItemStack s = new ItemStack(it, Math.max(1, a.count));
                List<String> lore = new ArrayList<>();
                lore.add("Seller: " + a.sellerName);
                lore.add(a.bid == 0 ? "Starting bid: " + a.start + " " + ore.label
                                    : "Current bid: " + a.bid + " " + ore.label + " (" + a.bidderName + ")");
                lore.add("Time left: " + Util.timeLeft(a.end));
                if (mine) lore.add(a.bidder == null ? "Click to cancel" : "Has bids - can't cancel");
                else {
                    lore.add("Click: bid " + (a.bid == 0 ? a.start : a.bid + 1) + " " + ore.label);
                    lore.add("Shift-click: bid +5");
                    if (owner) lore.add("OWNER: right-click to remove");
                }
                s.set(DataComponents.LORE, Util.loreOf(lore));
                m.set(slot++, s, (pl, t) -> {
                    if (mine) Auctions.cancel(pl, a);
                    else if (m.lastButton == 1 && isOwner(pl)) Auctions.adminRemove(pl, a);
                    else Auctions.bid(pl, a, t == ClickType.QUICK_MOVE ? 5 : 1);
                    bids(pl, mine);
                });
            }
            m.set(45, Util.icon(Items.ARROW, 1, "Back", ChatFormatting.RED, null), (pl, t) -> main(pl));
            m.set(49, Util.icon(Items.EMERALD_BLOCK, 1, "Create Auction", ChatFormatting.GREEN,
                    List.of("Auction the item in your hand.")), (pl, t) -> createAuction(pl));
            m.set(53, Util.icon(Items.CLOCK, 1, "Refresh", ChatFormatting.YELLOW, null), (pl, t) -> bids(pl, mine));
        });
    }

    // ================================================================ create auction (no commands needed)
    private static class Draft {
        Ore ore = Ore.IRON;
        int start = 1;
        int minutes = 60;
    }

    private static final int[] DURATIONS = {5, 15, 30, 60, 180, 360, 720, 1440, 4320};

    private static String fmtMinutes(int mins) {
        if (mins < 60) return mins + " minutes";
        if (mins % 60 == 0) return (mins / 60) + (mins == 60 ? " hour" : " hours");
        return mins + " minutes";
    }

    public static void createAuction(ServerPlayer p) {
        Draft d = new Draft();
        open(p, "Create Auction", 3, m -> drawCreate(m, p, d));
    }

    private static void drawCreate(GuiMenu m, ServerPlayer p, Draft d) {
        m.onInvChange = () -> drawCreate(m, p, d);
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty()) {
            m.set(4, Util.icon(Items.BARRIER, 1, "Nothing in your hand!", ChatFormatting.RED,
                    List.of("Close this, hold the item you want", "to sell, then open Create Auction again.")), null);
        } else {
            ItemStack s = hand.copy();
            s.set(DataComponents.LORE, Util.loreOf(List.of("This is what gets auctioned.", "The whole stack is listed.")));
            m.set(4, s, null);
        }
        m.set(10, Util.icon(d.ore.item, 1, "Currency: " + d.ore.label, ChatFormatting.GOLD,
                List.of("Left-click: next ore", "Right-click: previous ore")), (pl, t) -> {
            int n = Ore.values().length;
            d.ore = Ore.values()[(d.ore.ordinal() + (m.lastButton == 1 ? n - 1 : 1)) % n];
            drawCreate(m, pl, d);
        });
        m.set(12, Util.icon(Items.GOLD_NUGGET, 1, "Starting bid: " + d.start + " " + d.ore.label, ChatFormatting.YELLOW,
                List.of("Left: +1   Right: -1", "Shift-left: +10   Shift-right: -10")), (pl, t) -> {
            d.start = Math.max(1, Math.min(100000, d.start + delta(m, t, 1, 10)));
            drawCreate(m, pl, d);
        });
        m.set(14, Util.icon(Items.CLOCK, 1, "Duration: " + fmtMinutes(d.minutes), ChatFormatting.AQUA,
                List.of("Left-click: longer", "Right-click: shorter")), (pl, t) -> {
            int idx = 0;
            for (int i = 0; i < DURATIONS.length; i++) if (DURATIONS[i] == d.minutes) idx = i;
            idx = Math.max(0, Math.min(DURATIONS.length - 1, idx + (m.lastButton == 1 ? -1 : 1)));
            d.minutes = DURATIONS[idx];
            drawCreate(m, pl, d);
        });
        m.set(16, Util.icon(Items.EMERALD_BLOCK, 1, "CONFIRM", ChatFormatting.GREEN,
                List.of("List it now!")), (pl, t) -> {
            if (Auctions.create(pl, d.ore.name(), d.start, d.minutes)) bids(pl, true);
        });
        m.set(22, Util.icon(Items.ARROW, 1, "Back", ChatFormatting.RED, null), (pl, t) -> bids(pl, true));
    }

    // ================================================================ shop (player market) with filters + search
    private static class View {
        Ore ore = null;          // null = all ores
        int sort = 0;            // 0 newest, 1 price low->high, 2 price high->low
        String query = "";
        int page = 0;
    }

    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static final String[] SORT_NAMES = {"Newest first", "Price: low to high", "Price: high to low"};

    private static View view(ServerPlayer p) { return VIEWS.computeIfAbsent(p.getUUID(), k -> new View()); }

    public static void searchShop(ServerPlayer p, String text) {
        View v = view(p);
        v.query = text.toLowerCase().trim();
        v.page = 0;
        market(p);
    }

    private static long worth(Data.Listing l) { return (long) Ore.parse(l.ore).value * l.price; }

    private static List<Data.Listing> filtered(View v) {
        List<Data.Listing> list = new ArrayList<>();
        for (Data.Listing l : Data.store.listings) {
            if (Util.item(l.item) == Items.AIR) continue;
            if (v.ore != null && !v.ore.name().equals(l.ore)) continue;
            if (!v.query.isEmpty()) {
                String hay = (nice(l.item) + " " + l.sellerName).toLowerCase();
                if (!hay.contains(v.query)) continue;
            }
            list.add(l);
        }
        if (v.sort == 1) list.sort((a, b) -> Long.compare(worth(a), worth(b)));
        else if (v.sort == 2) list.sort((a, b) -> Long.compare(worth(b), worth(a)));
        else list.sort((a, b) -> Integer.compare(b.id, a.id));
        return list;
    }

    public static void market(ServerPlayer p) {
        String title = Data.layout.shopTitle + (Market.isTest(p) ? " [TEST MODE]" : "");
        open(p, title, 6, m -> drawMarket(m, p, view(p)));
    }

    private static void drawMarket(GuiMenu m, ServerPlayer p, View v) {
        String me = p.getUUID().toString();
        boolean owner = isOwner(p);
        boolean test = Market.isTest(p);
        List<Data.Listing> list = filtered(v);
        int pages = Math.max(1, (list.size() + 44) / 45);
        if (v.page >= pages) v.page = pages - 1;
        if (v.page < 0) v.page = 0;
        int start = v.page * 45;

        for (int i = 0; i < 45; i++) {
            int idx = start + i;
            if (idx >= list.size()) { m.clear(i); continue; }
            Data.Listing l = list.get(idx);
            boolean mine = me.equals(l.seller);
            Item it = Util.item(l.item);
            ItemStack s = new ItemStack(it, Math.max(1, l.count));
            List<String> lore = new ArrayList<>();
            lore.add("Seller: " + l.sellerName);
            lore.add("Price: " + Market.priceText(l));
            if (mine) {
                lore.add("Your listing - right-click to take it back");
                if (test) lore.add("TEST MODE: left-click to buy it yourself");
            } else {
                lore.add("Left-click: buy");
            }
            if (owner && !mine) lore.add("OWNER: right-click to REMOVE this listing");
            s.set(DataComponents.LORE, Util.loreOf(lore));
            m.set(i, s, (pl, t) -> {
                if (m.lastButton == 1) {
                    if (mine) Market.cancel(pl, l);
                    else if (isOwner(pl)) Market.adminRemove(pl, l);
                } else {
                    Market.buy(pl, l);
                }
                drawMarket(m, pl, v);
            });
        }

        // ----- bottom control row -----
        m.set(45, Util.icon(Items.ARROW, 1, "Back", ChatFormatting.RED, null), (pl, t) -> main(pl));
        m.set(46, Util.icon(Items.SPECTRAL_ARROW, 1, "Previous page", ChatFormatting.YELLOW,
                List.of("Page " + (v.page + 1) + " / " + pages)), (pl, t) -> { v.page--; drawMarket(m, pl, v); });
        m.set(47, Util.icon(v.ore == null ? Items.HOPPER : v.ore.item, 1,
                "Filter: " + (v.ore == null ? "All ores" : v.ore.label + " only"), ChatFormatting.GOLD,
                List.of("Left-click: next ore", "Right-click: previous ore")), (pl, t) -> {
            int n = Ore.values().length + 1;
            int cur = v.ore == null ? 0 : v.ore.ordinal() + 1;
            cur = (cur + (m.lastButton == 1 ? n - 1 : 1)) % n;
            v.ore = cur == 0 ? null : Ore.values()[cur - 1];
            v.page = 0;
            drawMarket(m, pl, v);
        });
        m.set(48, Util.icon(Items.COMPARATOR, 1, "Sort: " + SORT_NAMES[v.sort], ChatFormatting.AQUA,
                List.of("Left-click: next", "Right-click: previous")), (pl, t) -> {
            v.sort = (v.sort + (m.lastButton == 1 ? 2 : 1)) % 3;
            v.page = 0;
            drawMarket(m, pl, v);
        });
        m.set(49, Util.icon(Items.EMERALD_BLOCK, 1, "List an Item", ChatFormatting.GREEN,
                List.of("Sell the item in your hand", "for a fixed price.")), (pl, t) -> listItem(pl));
        m.set(50, Util.icon(Items.COMPASS, 1, "Search: " + (v.query.isEmpty() ? "(none)" : v.query), ChatFormatting.LIGHT_PURPLE,
                List.of("Left-click: type a search in chat", "Right-click: clear search")), (pl, t) -> {
            if (m.lastButton == 1) {
                v.query = "";
                v.page = 0;
                drawMarket(m, pl, v);
            } else {
                Prompt.ask(pl, "Type the item (or seller) you want to find", txt -> {
                    v.query = txt.toLowerCase();
                    v.page = 0;
                    market(pl);
                });
            }
        });
        m.set(51, Util.icon(Items.PAPER, 1, "Your ores", ChatFormatting.WHITE, balance(p)), null);
        m.set(52, Util.icon(Items.SPECTRAL_ARROW, 1, "Next page", ChatFormatting.YELLOW,
                List.of("Page " + (v.page + 1) + " / " + pages)), (pl, t) -> { v.page++; drawMarket(m, pl, v); });
        m.set(53, Util.icon(Items.CLOCK, 1, "Refresh", ChatFormatting.YELLOW,
                List.of(list.size() + " listing(s) shown")), (pl, t) -> drawMarket(m, pl, v));
    }

    // ================================================================ list an item
    private static class ListDraft {
        Ore ore = Ore.IRON;
        int price = 1;
        String extraId = null;   // registry id of the optional extra item, null = none
        int extraCount = 16;
    }

    public static void listItem(ServerPlayer p) { listItem(p, new ListDraft()); }

    private static void listItem(ServerPlayer p, ListDraft d) {
        open(p, "List an Item", 3, m -> drawList(m, p, d));
    }

    private static String draftText(ListDraft d) {
        StringBuilder sb = new StringBuilder();
        if (d.price > 0) sb.append(d.price).append(' ').append(d.ore.label);
        if (d.extraId != null) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(d.extraCount).append(' ').append(nice(d.extraId));
        }
        return sb.length() == 0 ? "nothing yet" : sb.toString();
    }

    private static void drawList(GuiMenu m, ServerPlayer p, ListDraft d) {
        m.onInvChange = () -> drawList(m, p, d);
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty()) {
            m.set(4, Util.icon(Items.BARRIER, 1, "Nothing in your hand!", ChatFormatting.RED,
                    List.of("Hold the item you want to sell", "(you can move items in your inventory", "while this menu is open).")), null);
        } else {
            ItemStack s = hand.copy();
            s.set(DataComponents.LORE, Util.loreOf(List.of("This is what gets listed.", "The whole stack is sold together.")));
            m.set(4, s, null);
        }
        m.set(10, Util.icon(d.ore.item, 1, "Currency: " + d.ore.label, ChatFormatting.GOLD,
                List.of("Left-click: next ore", "Right-click: previous ore")), (pl, t) -> {
            int n = Ore.values().length;
            d.ore = Ore.values()[(d.ore.ordinal() + (m.lastButton == 1 ? n - 1 : 1)) % n];
            drawList(m, pl, d);
        });
        m.set(11, Util.icon(Items.GOLD_NUGGET, 1, "Ore price: " + d.price + " " + d.ore.label, ChatFormatting.YELLOW,
                List.of("Left: +1   Right: -1", "Shift-left: +10   Shift-right: -10", "Can be 0 if you set an extra item.")), (pl, t) -> {
            d.price = Math.max(0, Math.min(100000, d.price + delta(m, t, 1, 10)));
            drawList(m, pl, d);
        });
        Item extraItem = d.extraId == null ? Items.AIR : Util.item(d.extraId);
        if (extraItem == Items.AIR) { d.extraId = null; extraItem = Items.BARRIER; }
        m.set(13, Util.icon(extraItem, 1,
                "Extra item price: " + (d.extraId == null ? "none (optional)" : nice(d.extraId)), ChatFormatting.GREEN,
                List.of("Optional! Buyer also pays this item.", "Left-click: pick from every item in the game",
                        "Right-click: remove the extra item")), (pl, t) -> {
            if (m.lastButton == 1) {
                d.extraId = null;
                drawList(m, pl, d);
            } else {
                pickItem(pl, new Pick(), picked -> { d.extraId = Util.id(picked); listItem(pl, d); }, () -> listItem(pl, d));
            }
        });
        m.set(14, Util.icon(Items.CHEST, 1, "Extra item amount: " + d.extraCount, ChatFormatting.AQUA,
                List.of("Left: +1   Right: -1", "Shift-left: +16   Shift-right: -16")), (pl, t) -> {
            d.extraCount = Math.max(1, Math.min(2304, d.extraCount + delta(m, t, 1, 16)));
            drawList(m, pl, d);
        });
        m.set(16, Util.icon(Items.EMERALD_BLOCK, 1, "CONFIRM", ChatFormatting.GREEN,
                List.of("Price: " + draftText(d), "Click to put it in the shop!")), (pl, t) -> {
            if (Market.create(pl, d.ore.name(), d.price, d.extraId, d.extraCount)) market(pl);
        });
        m.set(22, Util.icon(Items.ARROW, 1, "Back", ChatFormatting.RED, null), (pl, t) -> market(pl));
    }

    // ================================================================ item picker (every item in the game)
    private static class Pick {
        String query = "";
        int page = 0;
    }

    private static List<Item> ALL_ITEMS;

    /** Every registered item (vanilla first, then modded), alphabetical. Built once. */
    private static List<Item> allItems() {
        if (ALL_ITEMS == null) {
            List<Item> l = new ArrayList<>();
            for (Item i : BuiltInRegistries.ITEM) if (i != Items.AIR) l.add(i);
            l.sort(Comparator.comparing((Item i) -> !Util.id(i).startsWith("minecraft:")).thenComparing(i -> Util.id(i)));
            ALL_ITEMS = l;
        }
        return ALL_ITEMS;
    }

    private static List<Item> pickList(Pick st) {
        if (st.query.isEmpty()) return allItems();
        List<Item> out = new ArrayList<>();
        for (Item i : allItems()) if (nice(Util.id(i)).contains(st.query)) out.add(i);
        return out;
    }

    private static void pickItem(ServerPlayer p, Pick st, Consumer<Item> onPick, Runnable onBack) {
        open(p, "Choose an item", 6, m -> drawPick(m, p, st, onPick, onBack));
    }

    private static void drawPick(GuiMenu m, ServerPlayer p, Pick st, Consumer<Item> onPick, Runnable onBack) {
        List<Item> list = pickList(st);
        int pages = Math.max(1, (list.size() + 44) / 45);
        if (st.page >= pages) st.page = pages - 1;
        if (st.page < 0) st.page = 0;
        int start = st.page * 45;

        for (int i = 0; i < 45; i++) {
            int idx = start + i;
            if (idx >= list.size()) { m.clear(i); continue; }
            Item it = list.get(idx);
            ItemStack s = new ItemStack(it);
            s.set(DataComponents.LORE, Util.loreOf(List.of(Util.id(it), "", "Click to select")));
            m.set(i, s, (pl, t) -> onPick.accept(it));
        }
        if (list.isEmpty()) {
            m.set(22, Util.icon(Items.BARRIER, 1, "No items match \"" + st.query + "\"", ChatFormatting.RED,
                    List.of("Right-click the search button", "to clear the search.")), null);
        }

        // ----- bottom control row -----
        m.set(45, Util.icon(Items.ARROW, 1, "Back", ChatFormatting.RED,
                List.of("Go back without choosing.")), (pl, t) -> onBack.run());
        m.set(48, Util.icon(Items.SPECTRAL_ARROW, 1, "Previous page", ChatFormatting.YELLOW,
                List.of("Page " + (st.page + 1) + " / " + pages, "Shift-click: back 5 pages")), (pl, t) -> {
            st.page -= (t == ClickType.QUICK_MOVE ? 5 : 1);
            drawPick(m, pl, st, onPick, onBack);
        });
        m.set(49, Util.icon(Items.OAK_SIGN, 1, "Search: " + (st.query.isEmpty() ? "(none)" : st.query), ChatFormatting.LIGHT_PURPLE,
                List.of(list.size() + " item(s) shown", "Left-click: type a search in chat", "Right-click: clear search")), (pl, t) -> {
            if (m.lastButton == 1) {
                st.query = "";
                st.page = 0;
                drawPick(m, pl, st, onPick, onBack);
            } else {
                Prompt.ask(pl, "Type the item you want to find", txt -> {
                    st.query = txt.toLowerCase().trim().replace('_', ' ');
                    st.page = 0;
                    pickItem(pl, st, onPick, onBack);
                });
            }
        });
        m.set(50, Util.icon(Items.SPECTRAL_ARROW, 1, "Next page", ChatFormatting.YELLOW,
                List.of("Page " + (st.page + 1) + " / " + pages, "Shift-click: forward 5 pages")), (pl, t) -> {
            st.page += (t == ClickType.QUICK_MOVE ? 5 : 1);
            drawPick(m, pl, st, onPick, onBack);
        });
        m.set(53, Util.icon(Items.PAPER, 1, "Page " + (st.page + 1) + " / " + pages, ChatFormatting.WHITE, null), null);
    }

    // ================================================================ owner menu (OP only)
    public static void ownerMenu(ServerPlayer p) {
        open(p, "Owner Menu", 3, m -> drawOwnerMenu(m, p));
    }

    private static void drawOwnerMenu(GuiMenu m, ServerPlayer p) {
        boolean test = Market.TEST.contains(p.getUUID());
        m.set(4, Util.icon(Items.PAPER, 1, "Owner tools", ChatFormatting.RED,
                List.of("Only OPs can open this.")), null);
        m.set(10, Util.icon(test ? Items.LIME_DYE : Items.GRAY_DYE, 1, "Test mode: " + (test ? "ON" : "OFF"),
                test ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                List.of("When ON you can buy your OWN listings", "in the shop. You pay yourself, so you",
                        "lose nothing - perfect for testing.")), (pl, t) -> {
            if (!Market.TEST.remove(pl.getUUID())) Market.TEST.add(pl.getUUID());
            drawOwnerMenu(m, pl);
        });
        m.set(12, Util.icon(Items.BARREL, 1, "Moderate shop", ChatFormatting.LIGHT_PURPLE,
                List.of("Open the shop.", "Right-click any listing to REMOVE it.", "(the item goes back to the seller)")), (pl, t) -> market(pl));
        m.set(14, Util.icon(Items.GOLD_INGOT, 1, "Moderate auctions", ChatFormatting.GOLD,
                List.of("Open the auctions.", "Right-click any auction to REMOVE it.")), (pl, t) -> bids(pl, false));
        m.set(16, Util.icon(Items.PAINTING, 1, "Customize GUI", ChatFormatting.AQUA,
                List.of("Change titles, icons, names,", "background and border.")), (pl, t) -> customize(pl));
        m.set(22, Util.icon(Items.ARROW, 1, "Back", ChatFormatting.RED, null), (pl, t) -> main(pl));
    }

    // ================================================================ owner: customize the whole GUI
    public static void customize(ServerPlayer p) {
        open(p, "Customize GUI", 6, m -> drawCustom(m, p));
    }

    private static void titleButton(GuiMenu m, int slot, String label, String current, Consumer<String> setter) {
        m.set(slot, Util.icon(Items.OAK_SIGN, 1, label, ChatFormatting.YELLOW,
                List.of("Now: " + current, "Click, then type the new title in chat.", "Use & for colors, e.g. &6Gold &bAqua")),
                (pl, t) -> Prompt.ask(pl, "Type the new " + label + ":", txt -> {
                    setter.accept(colorize(txt));
                    Data.saveLayout();
                    customize(pl);
                }));
    }

    private static void buttonCustom(GuiMenu m, int slot, String key, Item defIcon, String defName) {
        m.set(slot, Util.icon(iconFor(key, defIcon), 1, "Button: " + nameFor(key, defName), ChatFormatting.WHITE,
                List.of("Left-click: use the item in your hand as icon",
                        "Right-click: rename (type in chat)", "Shift-click: reset to default")), (pl, t) -> {
            if (t == ClickType.QUICK_MOVE) {
                Data.layout.icons.remove(key);
                Data.layout.names.remove(key);
                Data.saveLayout();
                Util.msg(pl, "Button reset.", ChatFormatting.GREEN);
                drawCustomAfter(m, pl);
            } else if (m.lastButton == 1) {
                Prompt.ask(pl, "Type the new name for this button (& for colors)", txt -> {
                    Data.layout.names.put(key, colorize(txt));
                    Data.saveLayout();
                    customize(pl);
                });
            } else {
                ItemStack h = pl.getMainHandItem();
                if (h.isEmpty()) { Util.msg(pl, "Hold an item to use as the icon.", ChatFormatting.RED); return; }
                Data.layout.icons.put(key, Util.id(h.getItem()));
                Data.saveLayout();
                Util.msg(pl, "Icon updated.", ChatFormatting.GREEN);
                drawCustomAfter(m, pl);
            }
        });
    }

    private static void drawCustomAfter(GuiMenu m, ServerPlayer p) { drawCustom(m, p); }

    private static void drawCustom(GuiMenu m, ServerPlayer p) {
        titleButton(m, 10, "MAIN MENU title", Data.layout.mainTitle, s -> Data.layout.mainTitle = s);
        titleButton(m, 11, "SHOP title", Data.layout.shopTitle, s -> Data.layout.shopTitle = s);
        titleButton(m, 12, "AUCTIONS title", Data.layout.bidsTitle, s -> Data.layout.bidsTitle = s);

        buttonCustom(m, 19, "shop", Items.CHEST, "Shop");
        buttonCustom(m, 20, "bids", Items.GOLD_INGOT, "Bids");
        buttonCustom(m, 21, "mine", Items.WRITABLE_BOOK, "My Auctions");

        Item f = Util.item(Data.layout.filler);
        if (f == Items.AIR) f = Items.GRAY_STAINED_GLASS_PANE;
        m.set(28, Util.icon(f, 1, "Background item", ChatFormatting.WHITE,
                List.of("Left-click: use the item in your hand", "Shift-click: reset")), (pl, t) -> {
            if (t == ClickType.QUICK_MOVE) {
                Data.layout.filler = "minecraft:gray_stained_glass_pane";
            } else {
                ItemStack h = pl.getMainHandItem();
                if (h.isEmpty()) { Util.msg(pl, "Hold an item first.", ChatFormatting.RED); return; }
                Data.layout.filler = Util.id(h.getItem());
            }
            Data.saveLayout();
            drawCustom(m, pl);
        });

        Item bd = Data.layout.border.isEmpty() ? Items.BARRIER : Util.item(Data.layout.border);
        if (bd == Items.AIR) bd = Items.BARRIER;
        m.set(29, Util.icon(bd, 1, "Border item", ChatFormatting.WHITE,
                List.of("Outer ring of every menu.", "Left-click: use the item in your hand",
                        "Shift-click: no special border")), (pl, t) -> {
            if (t == ClickType.QUICK_MOVE) {
                Data.layout.border = "";
            } else {
                ItemStack h = pl.getMainHandItem();
                if (h.isEmpty()) { Util.msg(pl, "Hold an item first.", ChatFormatting.RED); return; }
                Data.layout.border = Util.id(h.getItem());
            }
            Data.saveLayout();
            drawCustom(m, pl);
        });

        boolean snd = Data.layout.clickSounds;
        m.set(30, Util.icon(snd ? Items.NOTE_BLOCK : Items.BARRIER, 1, "Click sounds: " + (snd ? "ON" : "OFF"),
                snd ? ChatFormatting.GREEN : ChatFormatting.RED, List.of("Click to toggle the ding.")), (pl, t) -> {
            Data.layout.clickSounds = !Data.layout.clickSounds;
            Data.saveLayout();
            drawCustom(m, pl);
        });

        m.set(40, Util.icon(Items.ENDER_EYE, 1, "Preview main menu", ChatFormatting.LIGHT_PURPLE, null), (pl, t) -> main(pl));
        m.set(49, Util.icon(Items.ARROW, 1, "Back", ChatFormatting.RED, null), (pl, t) -> ownerMenu(pl));
    }
}

package com.example.serverstore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("serverstore")
@EventBusSubscriber(modid = "serverstore")
public class ServerStore {
    private static int ticks = 0;

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        Auctions.server = e.getServer();
        Data.load();
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        Data.save();
        Data.saveLayout();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) Auctions.claim(sp);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (++ticks % 20 == 0) Auctions.tick();
    }

    /** Catches what a player types when a menu asked them a question (search, rename...). */
    @SubscribeEvent
    public static void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        if (!Prompt.isWaiting(p)) return;
        String text = e.getRawText();
        e.setCanceled(true);
        Auctions.server.execute(() -> Prompt.handle(p, text));
    }

    // If this line fails to compile, replace the body with:  return src.hasPermission(2);
    public static boolean isOp(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();

        d.register(Commands.literal("shop")
                .executes(c -> {
                    Gui.main(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("search")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(c -> {
                                    Gui.searchShop(c.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(c, "text"));
                                    return 1;
                                }))));

        d.register(Commands.literal("owner")
                .requires(ServerStore::isOp)
                .executes(c -> {
                    Gui.ownerMenu(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("shopedit").executes(c -> {
                    Gui.ownerMenu(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("title")
                        .then(Commands.argument("which", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"main", "shop", "bids"}, b))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            String w = StringArgumentType.getString(c, "which");
                                            String t = StringArgumentType.getString(c, "text").replace('&', '\u00a7');
                                            switch (w) {
                                                case "main" -> Data.layout.mainTitle = t;
                                                case "shop" -> Data.layout.shopTitle = t;
                                                case "bids" -> Data.layout.bidsTitle = t;
                                                default -> { c.getSource().sendFailure(Component.literal("Use main, shop or bids")); return 0; }
                                            }
                                            Data.saveLayout();
                                            c.getSource().sendSuccess(() -> Component.literal("Title updated."), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("filler")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(c -> {
                                    String id = StringArgumentType.getString(c, "item");
                                    if (!id.contains(":")) id = "minecraft:" + id;
                                    Item it = Util.item(id);
                                    if (it == Items.AIR) {
                                        c.getSource().sendFailure(Component.literal("Unknown item."));
                                        return 0;
                                    }
                                    Data.layout.filler = id;
                                    Data.saveLayout();
                                    c.getSource().sendSuccess(() -> Component.literal("Filler updated."), false);
                                    return 1;
                                })))
                .then(Commands.literal("reload").executes(c -> {
                    Data.load();
                    c.getSource().sendSuccess(() -> Component.literal("Reloaded."), false);
                    return 1;
                })));
    }
}

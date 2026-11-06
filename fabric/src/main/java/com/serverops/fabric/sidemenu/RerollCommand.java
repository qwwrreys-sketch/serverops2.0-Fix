package com.serverops.fabric.sidemenu;

import java.util.Collection;
import java.util.Locale;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /rerolls give|take|set|check  -  OWNER ONLY.
 *
 * <pre>
 *   /rerolls give  &lt;player&gt; &lt;amount&gt;   add ReRolls
 *   /rerolls take  &lt;player&gt; &lt;amount&gt;   remove ReRolls (refused if the player has fewer)
 *   /rerolls set   &lt;player&gt; &lt;amount&gt;   set the exact number
 *   /rerolls check &lt;player&gt;            show the current number
 * </pre>
 *
 * <h3>Who may run it</h3>
 * <ul>
 *   <li>the player whose UUID is written in {@link #OWNER_UUID} below - and nobody else, not even other OPs;</li>
 *   <li>the server console (hosting terminal, RCON, store plug-ins that run commands as the console).</li>
 * </ul>
 * Anyone else gets the red error "Error: Only the Server Owner/Console can manage ReRolls!".
 *
 * <p>The check lives INSIDE every command, not in {@code .requires}, because {@code .requires} would make
 * vanilla answer "Unknown command" instead of your message. Command blocks and functions are refused too
 * (they have no player and only OP level 2 by default).
 *
 * <p>All changes go through {@link MenuService#setRerolls}, i.e. the same data and saving as the Character menu,
 * and an online player sees the new number instantly.
 */
final class RerollCommand {

    // =====================================================================================
    //  PASTE YOUR MINECRAFT UUID HERE (with or without dashes, upper or lower case is fine).
    //  Find it at https://mcuuid.net or in the server's usercache.json.
    //  The owner UUID is already filled in below. (If it were left as YOUR-UUID-HERE, nobody could use the
    //  commands in-game and only the console could.)
    // =====================================================================================
    private final String OWNER_UUID = "89cb47f3-4afd-41a8-a048-39cde2f5339c";

    private static final String DENIED = "Error: Only the Server Owner/Console can manage ReRolls!";

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rerolls")
                .executes(ctx -> usage(ctx, "/rerolls <give|take|set|check> <player> [amount]"))

                .then(Commands.literal("give")
                        .executes(ctx -> usage(ctx, "/rerolls give <player> <amount>"))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> usage(ctx, "/rerolls give <player> <amount>"))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MenuService.MAX_REROLLS))
                                        .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))

                .then(Commands.literal("take")
                        .executes(ctx -> usage(ctx, "/rerolls take <player> <amount>"))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> usage(ctx, "/rerolls take <player> <amount>"))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MenuService.MAX_REROLLS))
                                        .executes(ctx -> take(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))

                .then(Commands.literal("set")
                        .executes(ctx -> usage(ctx, "/rerolls set <player> <amount>"))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> usage(ctx, "/rerolls set <player> <amount>"))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, MenuService.MAX_REROLLS))
                                        .executes(ctx -> set(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))

                .then(Commands.literal("check")
                        .executes(ctx -> usage(ctx, "/rerolls check <player>"))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(this::check))));
    }

    // ------------------------------------------------------------------ the owner lock

    /** Is this the server console / RCON? They have no entity and the full level-4 permission. */
    private static boolean isConsole(CommandSourceStack src) {
        return src.getEntity() == null && src.hasPermission(4);
    }

    /** Is this the player whose UUID is pasted into OWNER_UUID? Dashes and letter case do not matter. */
    private boolean isOwner(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer p)) return false;
        return normalise(p.getUUID().toString()).equals(normalise(OWNER_UUID));
    }

    private static String normalise(String uuid) {
        return uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    /** @return true (after telling the sender in red) when the sender is neither the owner nor the console. */
    private boolean denied(CommandSourceStack src) {
        if (isConsole(src) || isOwner(src)) return false;
        src.sendFailure(Component.literal(DENIED)); // sendFailure prints it in red
        return true;
    }

    private int usage(CommandContext<CommandSourceStack> ctx, String text) {
        if (denied(ctx.getSource())) return 0;
        ctx.getSource().sendSuccess(() -> Component.literal("Usage: " + text).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    // ------------------------------------------------------------------ the four commands

    private int give(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (denied(src)) return 0;
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "player");
        for (GameProfile t : targets) {
            long now = (long) MenuService.getRerolls(t.getId()) + amount;
            int result = MenuService.setRerolls(t.getId(), now, "You received " + amount + " ReRoll" + s(amount) + "!");
            tell(src, t, "You received " + amount + " ReRoll" + s(amount) + "! (You now have " + result + ".)", ChatFormatting.GREEN);
        }
        feedback(src, "Successfully gave " + amount + " ReRoll" + s(amount) + " to " + who(targets) + ".");
        return targets.size();
    }

    private int take(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (denied(src)) return 0;
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "player");
        int done = 0;
        for (GameProfile t : targets) {
            int have = MenuService.getRerolls(t.getId());
            if (have < amount) { // never half-take: refuse and say why
                src.sendFailure(Component.literal(t.getName() + " only has " + have + " ReRoll" + s(have)
                        + ", so " + amount + " cannot be taken. Use /rerolls set to set it exactly."));
                continue;
            }
            int result = MenuService.setRerolls(t.getId(), (long) have - amount, amount + " ReRoll" + s(amount) + " were taken from you.");
            tell(src, t, amount + " ReRoll" + s(amount) + " were taken from you. (You now have " + result + ".)", ChatFormatting.YELLOW);
            done++;
        }
        if (done > 0) {
            feedback(src, "Successfully took " + amount + " ReRoll" + s(amount) + " from "
                    + (done == 1 && targets.size() == 1 ? who(targets) : done + " players") + ".");
        }
        return done;
    }

    private int set(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (denied(src)) return 0;
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "player");
        for (GameProfile t : targets) {
            int result = MenuService.setRerolls(t.getId(), amount, "Your ReRolls were set to " + amount + ".");
            tell(src, t, "Your ReRolls were set to " + result + ".", ChatFormatting.YELLOW);
        }
        feedback(src, "Successfully set the ReRolls of " + who(targets) + " to " + amount + ".");
        return targets.size();
    }

    private int check(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (denied(src)) return 0;
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "player");
        for (GameProfile t : targets) {
            int have = MenuService.getRerolls(t.getId());
            src.sendSuccess(() -> Component.literal(t.getName() + " has " + have + " ReRoll" + s(have) + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return targets.size();
    }

    // ------------------------------------------------------------------ small helpers

    /** Message for the sender (console / owner). {@code false}: other operators are NOT spammed with it. */
    private static void feedback(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
    }

    /** Chat message for the affected player, only when they are online. */
    private static void tell(CommandSourceStack src, GameProfile target, String text, ChatFormatting color) {
        ServerPlayer p = src.getServer().getPlayerList().getPlayer(target.getId());
        if (p != null) p.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private static String who(Collection<GameProfile> targets) {
        return targets.size() == 1 ? targets.iterator().next().getName() : targets.size() + " players";
    }

    private static String s(int n) {
        return n == 1 ? "" : "s";
    }
}

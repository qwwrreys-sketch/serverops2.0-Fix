package com.serverops.fabric.adminlog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.serverops.common.AccessRules;
import com.serverops.common.Perm;
import com.serverops.fabric.net.Net;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Tells every online admin what the other admins just did, and keeps a log file.
 *
 * <h3>How it hooks in without touching OpsService</h3>
 * A tiny mixin ({@code OpsServiceMixin}) calls {@link #actionStart} / {@link #actionEnd} (and the role / world
 * equivalents) around the panel's OWN handler methods. The packet path is not touched at all: the panel receives
 * and handles every request exactly as before. "Start" remembers the state, "end" compares it with the new state
 * and announces only what really changed (a refused action changes nothing, so it is never announced). Both
 * hooks only READ, never cancel or alter anything, and swallow their own errors.
 *
 * <p>Announcements go to every online player the panel's own access check lets in (except the admin who
 * acted), as a card in the top-right corner of their screen (optionally also a gold "[Staff]" chat line), and are appended to {@code <world>/serverops/admin-log.txt}.
 */
public final class AdminLogger {
    private AdminLogger() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record WorldReq(int kind, int value) {}

    /**
     * MASTER SWITCH. Set to false and rebuild to turn the whole notifier off (the hooks then return at once).
     * Handy for checking whether a problem has anything to do with it.
     */
    public static final boolean ENABLED = true;

    /** The "before" snapshot of the request that is being handled right now (server thread only). */
    private static Snap pending;
    private static boolean warned;

    // ================================================================== hooks (called by OpsServiceMixin)

    public static void actionStart(ServerPlayer actor, Net.Action in) {
        begin(actor, in.target());
    }

    public static void actionEnd(ServerPlayer actor, Net.Action in) {
        end(actor, in.target(), (s, b, a) -> reportAction(s, actor, in, b, a));
    }

    public static void roleStart(ServerPlayer actor) {
        begin(actor, null);
    }

    public static void roleEnd(ServerPlayer actor, Net.RoleEdit edit) {
        end(actor, null, (s, b, a) -> reportRoles(s, actor, edit, b, a));
    }

    public static void worldStart(ServerPlayer actor) {
        begin(actor, null);
    }

    public static void worldEnd(ServerPlayer actor, int kind, int value) {
        end(actor, null, (s, b, a) -> reportWorld(s, actor, new WorldReq(kind, value), b, a));
    }

    private interface Report {
        void run(MinecraftServer s, Snap before, Snap after);
    }

    /** Runs just BEFORE the panel's handler: remember how things look. Never throws, never changes anything. */
    private static void begin(ServerPlayer actor, UUID target) {
        pending = null;
        if (!ENABLED || !OpsBridge.available()) return;
        try {
            pending = Snap.take(actor.getServer(), actor, target);
        } catch (Throwable t) {
            warn(t);
        }
    }

    /** Runs just AFTER the panel's handler: announce whatever is different now. Never throws. */
    private static void end(ServerPlayer actor, UUID target, Report report) {
        Snap before = pending;
        pending = null;
        if (before == null) return;
        try {
            MinecraftServer s = actor.getServer();
            report.run(s, before, Snap.take(s, actor, target));
        } catch (Throwable t) {
            warn(t);
        }
    }

    private static void warn(Throwable t) {
        if (warned) return;
        warned = true;
        System.err.println("[ServerOps][AdminLog] Notification failed (the admin panel is not affected): " + t);
    }

    // ================================================================== what happened?

    private static void reportAction(MinecraftServer s, ServerPlayer actor, Net.Action in, Snap b, Snap a) {
        if (in.act() == null) return;
        String who = b.targetName.isEmpty() ? "someone" : b.targetName;
        boolean self = in.target().equals(actor.getUUID());
        String on = self ? "" : " for " + who;
        String key = in.target().toString();
        AdminLogNet.Kind kind = kindOf(in.act());

        switch (in.act()) {
            case BAN -> {
                if (a.banKeys.contains(key) && !b.banKeys.contains(key)) {
                    String reason = in.s1().isBlank() ? "Banned by staff." : clip(in.s1(), 256);
                    announce(s, actor, kind, "BANNED " + who + "  |  Reason: " + reason + "  |  Duration: " + duration(in.value()));
                }
            }
            case UNBAN -> {
                for (String k : b.banKeys) {
                    if (!a.banKeys.contains(k)) announce(s, actor, kind, "UNBANNED " + banName(s, b, k));
                }
            }
            case KICK -> {
                if (may(b, Perm.KICK, true) && !self) announce(s, actor, kind, "KICKED " + who);
            }
            case FREEZE -> {
                if (b.frozen != a.frozen) announce(s, actor, kind, (a.frozen ? "FROZE " : "UNFROZE ") + who);
            }
            case GOD -> {
                if (b.god != a.god) announce(s, actor, kind, "turned God Mode " + onOff(a.god) + on);
            }
            case INVIS -> {
                if (b.invis != a.invis) announce(s, actor, kind, "turned Invisibility " + onOff(a.invis) + on);
            }
            case FLY -> {
                if (b.fly != a.fly) announce(s, actor, kind, "turned Flight " + onOff(a.fly) + on);
            }
            case ESP -> {
                if (b.esp != a.esp) announce(s, actor, kind, "turned ESP " + onOff(a.esp) + on);
            }
            case HEALTHBARS -> {
                if (b.health != a.health) announce(s, actor, kind, "turned HP Bars " + onOff(a.health) + on);
            }
            case SET_ROLE -> {
                if (!b.roleId.equals(a.roleId)) {
                    announce(s, actor, kind, "changed the role of " + who + ": " + roleLabel(b, b.roleId) + " -> " + roleLabel(a, a.roleId));
                }
            }
            case PANEL -> {
                if (b.panelGrant != a.panelGrant) {
                    announce(s, actor, kind, (a.panelGrant ? "GRANTED admin panel access to " : "REVOKED admin panel access from ") + who);
                }
            }
            case HEAL -> {
                if (may(b, Perm.HEAL, true)) announce(s, actor, kind, "healed " + who);
            }
            case BRING -> {
                if (may(b, Perm.TELEPORT, true) && !self) announce(s, actor, kind, "teleported " + who + " to themselves");
            }
            case GOTO -> {
                if (may(b, Perm.TELEPORT, false) && !self) announce(s, actor, kind, "teleported to " + who);
            }
            case RESPAWN -> {
                if (may(b, Perm.RESPAWN, true)) announce(s, actor, kind, "forced " + who + " to respawn");
            }
            default -> { }
        }
    }

    private static void reportRoles(MinecraftServer s, ServerPlayer actor, Net.RoleEdit e, Snap b, Snap a) {
        if (b.rolesSig.equals(a.rolesSig) || e.op() == null) return;
        switch (e.op()) {
            case CREATE -> announce(s, actor, AdminLogNet.Kind.ROLE, "CREATED the role \"" + clip(e.name().trim(), 24) + "\"");
            case UPDATE -> announce(s, actor, AdminLogNet.Kind.ROLE, "EDITED the role \"" + a.roleNames.getOrDefault(e.id(), e.id()) + "\"");
            case DELETE -> announce(s, actor, AdminLogNet.Kind.ROLE, "DELETED the role \"" + b.roleNames.getOrDefault(e.id(), e.id()) + "\"");
            case REORDER -> announce(s, actor, AdminLogNet.Kind.ROLE, "REORDERED the role hierarchy");
            default -> { }
        }
    }

    private static void reportWorld(MinecraftServer s, ServerPlayer actor, WorldReq w, Snap b, Snap a) {
        switch (w.kind()) {
            case Net.WORLD_WEATHER -> {
                if (b.rain != a.rain || b.thunder != a.thunder) {
                    announce(s, actor, AdminLogNet.Kind.WORLD, "set the weather to " + (a.thunder ? "THUNDER" : a.rain ? "RAIN" : "CLEAR"));
                }
            }
            case Net.WORLD_TIME -> {
                if (b.dayTime != a.dayTime) announce(s, actor, AdminLogNet.Kind.WORLD, "set the time of day to " + (a.dayTime % 24000L));
            }
            case Net.WORLD_SEASON -> {
                if (!b.season.equals(a.season)) announce(s, actor, AdminLogNet.Kind.WORLD, "set the season to " + a.season);
            }
            default -> { }
        }
    }

    /**
     * For one-shot actions that leave no state behind (kick, heal, teleport, respawn): was the actor allowed?
     * Uses the panel's own access numbers plus the shared hierarchy rule from the 'common' module.
     */
    private static boolean may(Snap b, Perm perm, boolean hierarchy) {
        if (!b.targetOnline || !b.actor.panel() || !b.actor.has(perm)) return false;
        return !hierarchy || AccessRules.canTouch(b.actor.master(), b.actor.rank(), b.targetAcc.master(), b.targetAcc.rank());
    }

    private static AdminLogNet.Kind kindOf(Net.Act act) {
        return switch (act) {
            case BAN, UNBAN -> AdminLogNet.Kind.BAN;
            case FREEZE -> AdminLogNet.Kind.FREEZE;
            case GOD -> AdminLogNet.Kind.GOD;
            case INVIS -> AdminLogNet.Kind.INVIS;
            case ESP, HEALTHBARS -> AdminLogNet.Kind.VIEW;
            default -> AdminLogNet.Kind.OTHER;
        };
    }

    // ================================================================== wording

    private static String onOff(boolean on) {
        return on ? "ON" : "OFF";
    }

    private static String roleLabel(Snap s, String id) {
        return id.isEmpty() ? "no role" : s.roleNames.getOrDefault(id, id);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Minutes -> "permanent" or "1d 2h 30m". */
    static String duration(int minutes) {
        if (minutes <= 0) return "permanent";
        int d = minutes / 1440;
        int h = (minutes % 1440) / 60;
        int m = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 || sb.length() == 0) sb.append(m).append("m");
        return sb.toString().trim();
    }

    private static String banName(MinecraftServer s, Snap before, String key) {
        String n = before.banNames.get(key);
        if (n != null) return n;
        try {
            var cache = s.getProfileCache();
            if (cache != null) {
                Optional<GameProfile> p = cache.get(UUID.fromString(key));
                if (p.isPresent() && p.get().getName() != null) return p.get().getName();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return key;
    }

    // ================================================================== delivery

    /** Set to true to ALSO print every notice in the chat of the admins (the cards are the main display). */
    private static final boolean ALSO_SEND_CHAT = false;

    private static void announce(MinecraftServer s, ServerPlayer actor, AdminLogNet.Kind kind, String text) {
        String actorName = actor.getGameProfile().getName();
        String line = actorName + " " + text;
        System.out.println("[ServerOps][AdminLog] " + line);
        appendToFile(s, line);

        Component chat = Component.literal("[Staff] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(line).withStyle(ChatFormatting.YELLOW));
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p == actor) continue; // the acting admin already sees the result in their own panel
            if (!OpsBridge.isAdmin(p)) continue;
            FriendlyByteBuf buf = PacketByteBufs.create();
            new AdminLogNet.Toast(kind, actorName, text).write(buf);
            ServerPlayNetworking.send(p, AdminLogNet.S2C_TOAST, buf);
            if (ALSO_SEND_CHAT) p.sendSystemMessage(chat);
        }
    }

    private static void appendToFile(MinecraftServer s, String line) {
        try {
            Path f = s.getWorldPath(LevelResource.ROOT).resolve("serverops").resolve("admin-log.txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "[" + LocalDateTime.now().format(STAMP) + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[ServerOps][AdminLog] Could not write admin-log.txt: " + e);
        }
    }
}

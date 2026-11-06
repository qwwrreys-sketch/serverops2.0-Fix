package com.serverops.fabric.adminlog.mixin;

import com.serverops.fabric.adminlog.AdminLogger;
import com.serverops.fabric.net.Net;
import com.serverops.fabric.server.OpsService;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OBSERVE-ONLY hooks around the admin panel's three request handlers. Nothing is cancelled, replaced or
 * modified: the handler body runs untouched in between. {@code require = 0} means that if a method is ever
 * renamed the hook is simply skipped instead of crashing the game.
 */
@Mixin(OpsService.class)
public abstract class OpsServiceMixin {

    @Inject(method = "handleAction", at = @At("HEAD"), remap = false, require = 0)
    private static void serverops$actionStart(ServerPlayer actor, Net.Action in, CallbackInfo ci) {
        AdminLogger.actionStart(actor, in);
    }

    @Inject(method = "handleAction", at = @At("RETURN"), remap = false, require = 0)
    private static void serverops$actionEnd(ServerPlayer actor, Net.Action in, CallbackInfo ci) {
        AdminLogger.actionEnd(actor, in);
    }

    @Inject(method = "handleRole", at = @At("HEAD"), remap = false, require = 0)
    private static void serverops$roleStart(ServerPlayer actor, Net.RoleEdit edit, CallbackInfo ci) {
        AdminLogger.roleStart(actor);
    }

    @Inject(method = "handleRole", at = @At("RETURN"), remap = false, require = 0)
    private static void serverops$roleEnd(ServerPlayer actor, Net.RoleEdit edit, CallbackInfo ci) {
        AdminLogger.roleEnd(actor, edit);
    }

    @Inject(method = "handleWorld", at = @At("HEAD"), remap = false, require = 0)
    private static void serverops$worldStart(ServerPlayer actor, int kind, int value, CallbackInfo ci) {
        AdminLogger.worldStart(actor);
    }

    @Inject(method = "handleWorld", at = @At("RETURN"), remap = false, require = 0)
    private static void serverops$worldEnd(ServerPlayer actor, int kind, int value, CallbackInfo ci) {
        AdminLogger.worldEnd(actor, kind, value);
    }
}

package com.serverops.fabric.sidemenu;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * Server-side entrypoint of the side menu. It is registered as a SECOND "main" entrypoint in
 * fabric.mod.json, so ServerOpsMod / OpsService do not need a single changed line.
 */
public class MenuMod implements ModInitializer {
    @Override
    public void onInitialize() {
        MenuService.init();
        // /rerolls give|take|set|check  (owner UUID or server console only - see RerollCommand.OWNER_UUID)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> new RerollCommand().register(dispatcher));
    }
}

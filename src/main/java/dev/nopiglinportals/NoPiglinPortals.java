package dev.nopiglinportals;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NoPiglinPortals implements ModInitializer {
	public static final String MOD_ID = "nopiglinportals";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(PortalRules::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PortalRules.unload());
		CommandRegistrationCallback.EVENT.register(
			(dispatcher, buildContext, selection) -> PiglinPortalCommand.register(dispatcher, buildContext)
		);
	}
}

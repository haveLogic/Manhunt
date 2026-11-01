package islogic.manhunt;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Manhunt implements ModInitializer {
	public static final String MOD_ID = "manhunt";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static GameManager gameManager;   // ← 保存引用

	@Override
	public void onInitialize() {
		LOGGER.info("[Manhunt] 初始化");

		gameManager = new GameManager();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TeamManager.createTeams(server);
			GameState.initOnServerStart(server);
			gameManager.reset();
			PlayerResetManager.load(server);
		});
		Commands.register();
		PlayerJoinHandler.register();
		RunnerDeathHandler.register();
		ServerTickEvents.END_SERVER_TICK.register(new CompassTicker());
		ServerTickEvents.END_SERVER_TICK.register(gameManager);
		LOGGER.info("[Manhunt] CompassTicker registered");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

}

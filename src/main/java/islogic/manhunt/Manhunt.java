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

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Hello Fabric world!");

		// 监听服务器启动完成事件
        // 服务器完全启动后，调用创建队伍的方法
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TeamManager.createTeams(server);
			GameState.initOnServerStart(server);   // ← 缺
		});
		Commands.register();
		ServerTickEvents.END_SERVER_TICK.register(new CompassTicker());
		ServerTickEvents.END_SERVER_TICK.register(new GameManager());   // ← 缺
		LOGGER.info("[Manhunt] CompassTicker registered");  // ← 加这行
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

}

package islogic.manhunt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;

public class GameState {
    private static boolean gameStarted = false;

    public static boolean isGameStarted() {
        return gameStarted;
    }

    public static void setGameStarted(boolean started, MinecraftServer server) {
        if (gameStarted == started) return;
        gameStarted = started;

        // 直接使用 get().set() 设置规则值
        server.getGameRules().set(GameRules.ADVANCE_TIME, started, server);
    }

    public static void initOnServerStart(MinecraftServer server) {
        gameStarted = false;
        server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
    }
}
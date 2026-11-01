package islogic.manhunt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;

public class GameState {
    private static boolean gameStarted = false;

    public static boolean isGameStarted() {
        return gameStarted;
    }

    public static void setGameStarted(boolean started, MinecraftServer server) {
        gameStarted = started;

        // 不因 gameStarted 未变化而跳过，确保规则值始终与状态一致
        server.getGameRules().set(GameRules.ADVANCE_TIME, started, server);
    }

    public static void initOnServerStart(MinecraftServer server) {
        gameStarted = false;
        server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
    }
}
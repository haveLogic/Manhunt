package islogic.manhunt;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;

import java.util.List;

public class GameManager implements ServerTickEvents.EndTick {

    /** 三个互斥的阶段 */
    private enum Phase {
        WAITING,     // 等待开局：冻结玩家，检查开始条件
        RUNNING,     // 游戏进行中：什么都不做，只等 runner 死
        COUNTDOWN    // 重置倒计时中：每秒播报，归零就生成新世界
    }

    private static final int CHECK_INTERVAL = 5;
    private static final int RESET_DELAY_SECONDS = 30;

    private int tickCounter = 0;

    private Phase phase = Phase.WAITING;
    private int countdownStartTick = 0;
    private int lastBroadcastSecond = -1;

    @Override
    public void onEndTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

        switch (phase) {
            case WAITING   -> onWaiting(server);
            case RUNNING   -> onRunning(server);
            case COUNTDOWN -> onCountdown(server);
        }
    }

    /** 等待开局 */
    private void onWaiting(MinecraftServer server) {
        freezePlayers(server);

        List<ServerPlayer> hunters = TeamManager.getTeamPlayers(server, "hunters");
        List<ServerPlayer> runners = TeamManager.getRunners(server);

        if (!hunters.isEmpty() && !runners.isEmpty() && allPlayersHaveTeam(server)) {
            GameState.setGameStarted(true, server);
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("游戏开始！"), false);
            phase = Phase.RUNNING;
        }
    }

    /** 游戏进行中：只关心 runners 是否空了 */
    private void onRunning(MinecraftServer server) {
        if (TeamManager.getRunners(server).isEmpty()) {
            phase = Phase.COUNTDOWN;
            countdownStartTick = tickCounter;
            lastBroadcastSecond = -1;
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("所有 runner 已死亡，服务器将在 "
                            + RESET_DELAY_SECONDS + " 秒后生成新世界"), false);
        }
    }

    /** 倒计时中：每秒播报，归零就重置世界 */
    private void onCountdown(MinecraftServer server) {
        int elapsedTicks = tickCounter - countdownStartTick;
        int secondsLeft = RESET_DELAY_SECONDS - elapsedTicks / 20;

        if (secondsLeft <= 0) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("正在生成新世界，请稍候..."), false);
            try {
                WorldResetter.reset(server);
                phase = Phase.WAITING;
                lastBroadcastSecond = -1;
            } catch (Throwable t) {
                Manhunt.LOGGER.error("[Manhunt] 生成新世界失败", t);
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("生成新世界失败，服务器将关闭"), false);
                server.halt(false);
            }
            return;
        }

        if (secondsLeft != lastBroadcastSecond) {
            lastBroadcastSecond = secondsLeft;
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("服务器将在 " + secondsLeft + " 秒后生成新世界"), false);
        }
    }

    /** 每次服务器启动时调用，清空上一局残留的状态 */
    public void reset() {
        phase = Phase.WAITING;
        tickCounter = 0;
        countdownStartTick = 0;
        lastBroadcastSecond = -1;
    }

    private void freezePlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive()) continue;   // ← 关键：死亡玩家不碰
            float maxHealth = player.getMaxHealth();
            if (player.getHealth() < maxHealth) {
                player.setHealth(maxHealth);
            }

            FoodData food = player.getFoodData();
            if (food.getFoodLevel() < 20) {
                food.setFoodLevel(20);
            }
            if (food.getSaturationLevel() < 5.0F) {
                food.setSaturation(5.0F);
            }
        }
    }

    private static boolean allPlayersHaveTeam(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getTeam() == null) {
                return false;
            }
        }
        return true;
    }
}
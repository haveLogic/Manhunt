package islogic.manhunt;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;

import java.util.List;

public class GameManager implements ServerTickEvents.EndTick {

    private static final int CHECK_INTERVAL = 5;
    private int tickCounter = 0;

    @Override
    public void onEndTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

        // 只在游戏未开始时做事
        if (!GameState.isGameStarted()) {
            freezePlayers(server);
            checkStartCondition(server);
        }
    }

    /** 冻结玩家：血量、饥饿、消耗度强制重置 */
    private void freezePlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 只在血量不满时设置
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

    /** 至少 1 猎 1 跑时自动开始 */
    private void checkStartCondition(MinecraftServer server) {
        List<ServerPlayer> hunters = TeamManager.getTeamPlayers(server, "hunters");
        List<ServerPlayer> runners = TeamManager.getRunners(server);

        if (!hunters.isEmpty() && !runners.isEmpty() && allPlayersHaveTeam(server)) {
            GameState.setGameStarted(true, server);   // 传 server
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("游戏开始！"), false);
        }
    }

    // 判断：所有在线玩家都拥有队伍（没有人无队伍）
    private static boolean allPlayersHaveTeam(MinecraftServer server) {
        List<ServerPlayer> onlinePlayers = server.getPlayerList().getPlayers();
        for (ServerPlayer player : onlinePlayers) {
            if (player.getTeam() == null) {
                return false;
            }
        }
        return true;
    }


}
package islogic.manhunt;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

public class RunnerDeathHandler {

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayer player)) return;

            MinecraftServer server = player.level().getServer();
            if (server == null) return;

            Scoreboard scoreboard = server.getScoreboard();
            String name = player.getScoreboardName();

            PlayerTeam runnerTeam = null;
            for (PlayerTeam t : scoreboard.getPlayerTeams()) {
                if ("runners".equals(t.getName())) {
                    runnerTeam = t;
                    break;
                }
            }
            if (runnerTeam == null) return;

            if (runnerTeam.getPlayers().contains(name)) {
                scoreboard.removePlayerFromTeam(name, runnerTeam);
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("跑 " + name + " 已死亡，离开 runners 队伍"),
                        false
                );
            }
        });
    }
}
package islogic.manhunt;


import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TeamManager {
    public static void createTeams(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();

        // ---- Runners 队伍 ----
        PlayerTeam runners = scoreboard.getPlayerTeam("runners");
        if (runners == null) {
            runners = scoreboard.addPlayerTeam("runners");
            runners.setColor(Optional.of(TeamColor.GREEN));   // 绿色
            runners.setPlayerPrefix(
                    net.minecraft.network.chat.Component.literal("跑 ")
            );
        }

        // ---- Hunters 队伍 ----
        PlayerTeam hunters = scoreboard.getPlayerTeam("hunters");
        if (hunters == null) {
            hunters = scoreboard.addPlayerTeam("hunters");
            hunters.setColor(Optional.of(TeamColor.RED));     // 红色
            hunters.setPlayerPrefix(
                    net.minecraft.network.chat.Component.literal("猎 ")
            );
        }
    }

    /**
     * 通用方法：获取指定队伍中的所有在线玩家。
     * 注意：不能调用 scoreboard.getPlayerTeam(teamName)，它的参数是玩家名。
     */
    public static List<ServerPlayer> getTeamPlayers(MinecraftServer server, String teamName) {
        Scoreboard scoreboard = server.getScoreboard();
        List<ServerPlayer> result = new ArrayList<>();

        // 遍历所有队伍，按队伍名匹配
        PlayerTeam team = null;
        for (PlayerTeam t : scoreboard.getPlayerTeams()) {
            if (teamName.equals(t.getName())) {
                team = t;
                break;
            }
        }
        if (team == null) return result;

        // 遍历队伍成员名，查找在线玩家
        for (String memberName : team.getPlayers()) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(memberName);
            if (player != null) {
                result.add(player);
            }
        }
        return result;
    }

    public static List<ServerPlayer> getRunners(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        List<ServerPlayer> runners = new ArrayList<>();

        // 1. 找到名为 "runners" 的队伍
        PlayerTeam runnerTeam = null;
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            if ("runners".equals(team.getName())) {
                runnerTeam = team;
                break;
            }
        }
        if (runnerTeam == null) return runners;

        // 2. 遍历队伍成员名，查找在线玩家
        for (String memberName : runnerTeam.getPlayers()) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(memberName);
            if (player != null) {
                runners.add(player);
            }
        }
        return runners;
    }
}
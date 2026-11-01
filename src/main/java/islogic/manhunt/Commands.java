package islogic.manhunt;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

public class Commands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(net.minecraft.commands.Commands.literal("hunters")
                    .executes(context -> {
                        int result = joinTeam(context.getSource(), "hunters");
                        if (result > 0) {
                            giveCompass(context.getSource());
                        }
                        return result;
                    })
            );

            dispatcher.register(net.minecraft.commands.Commands.literal("runners")
                    .executes(context -> {
                        int result = joinTeam(context.getSource(), "runners");
                        if (result > 0) {
                            removeCompass(context.getSource());
                        }
                        return result;
                    })
            );
        });
    }

    private static int joinTeam(CommandSourceStack source, String teamName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        MinecraftServer server = source.getServer();
        Scoreboard scoreboard = server.getScoreboard();

        PlayerTeam currentTeam = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (GameState.isGameStarted()) {
            if (currentTeam != null && teamName.equals(currentTeam.getName())) {
                source.sendSuccess(() -> Component.literal("你已经是 " + teamName + " 队伍成员"), false);
                return 1;
            }
            source.sendFailure(Component.literal("游戏进行中，不能更换队伍"));
            return 0;
        }

        // 按队伍名查找，不能调用 getPlayerTeam(teamName)（它的参数是玩家名）
        PlayerTeam team = null;
        for (PlayerTeam t : scoreboard.getPlayerTeams()) {
            if (teamName.equals(t.getName())) {
                team = t;
                break;
            }
        }
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }

        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
        source.sendSuccess(() -> Component.literal("已加入 " + teamName + " 队伍"), false);
        return 1;
    }

    private static void giveCompass(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        if (hasTrackerCompass(player)) {
            return;
        }

        ItemStack compass = CompassTicker.createTrackerCompass();
        if (!player.getInventory().add(compass)) {
            player.drop(compass, false, Prediction.SERVER_ONLY);
        }
    }

    private static void removeCompass(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (CompassTicker.isTrackerCompass(stack)) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static boolean hasTrackerCompass(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (CompassTicker.isTrackerCompass(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }
}

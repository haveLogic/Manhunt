package islogic.manhunt;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

public class Commands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(net.minecraft.commands.Commands.literal("hunters")
                    .executes(context -> {
                        int result = joinTeam(context.getSource(), "hunters");
                        giveCompass(context.getSource());
                        return result;
                    })
            );

            dispatcher.register(net.minecraft.commands.Commands.literal("runners")
                    .executes(context -> {
                        int result = joinTeam(context.getSource(), "runners");
                        removeCompass(context.getSource());
                        return result;
                    })
            );
        });
    }

    private static int joinTeam(CommandSourceStack source, String teamName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        MinecraftServer server = source.getServer();
        Scoreboard scoreboard = server.getScoreboard();

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

        // 按物品类型判断，忽略组件差异
        if (hasCompass(player)) {
            return;
        }

        ItemStack compass = new ItemStack(Items.COMPASS);
        if (!player.getInventory().add(compass)) {
            player.drop(compass, false, Prediction.SERVER_ONLY);
        }
    }

    private static void removeCompass(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.COMPASS)) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static boolean hasCompass(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.COMPASS)) {
                return true;
            }
        }
        return false;
    }
}

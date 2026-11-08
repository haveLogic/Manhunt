package islogic.manhunt;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class PlayerJoinHandler {

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.getPlayer();
            PlayerResetManager.onJoin(player);
            player.sendSystemMessage(
                    Component.literal("===== Manhunt =====")
                            .withStyle(ChatFormatting.GREEN)
            );
            if (GameState.isGameStarted()) {
                player.sendSystemMessage(
                        Component.literal("游戏正在进行中，可执行 /hunters 中途加入猎人队伍")
                                .withStyle(ChatFormatting.YELLOW)
                );
                player.sendSystemMessage(
                        Component.literal("本局不能加入跑者或更换队伍")
                                .withStyle(ChatFormatting.GRAY)
                );
            } else {
                player.sendSystemMessage(
                        Component.literal("使用 /hunters 成为猎人，或 /runners 成为跑者")
                                .withStyle(ChatFormatting.YELLOW)
                );
                player.sendSystemMessage(
                        Component.literal("选好队伍后游戏自动开始")
                                .withStyle(ChatFormatting.YELLOW)
                );
            }
        });
    }
}
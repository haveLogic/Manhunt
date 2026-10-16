package islogic.manhunt;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class PlayerJoinHandler {

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.getPlayer();
            player.sendSystemMessage(
                    Component.literal("===== Manhunt =====")
                            .withStyle(ChatFormatting.GREEN)
            );
            player.sendSystemMessage(
                    Component.literal("使用 /hunters 成为猎人，或 /runners 成为跑者")
                            .withStyle(ChatFormatting.YELLOW)
            );
            player.sendSystemMessage(
                    Component.literal("选好队伍后游戏自动开始")
                            .withStyle(ChatFormatting.YELLOW)
            );
        });
    }
}
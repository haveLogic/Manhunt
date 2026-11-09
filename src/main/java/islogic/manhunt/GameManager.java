package islogic.manhunt;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.BossEvent;
import net.minecraft.world.food.FoodData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class GameManager implements ServerTickEvents.EndTick {

    /** 三个互斥的阶段 */
    private enum Phase {
        WAITING,     // 等待开局：冻结玩家，检查开始条件
        RUNNING,     // 游戏进行中：什么都不做，只等 runner 死
        COUNTDOWN    // 重置倒计时中：Boss 栏显示倒计时，归零就生成新世界
    }

    private static final int CHECK_INTERVAL = 5;
    private static final int RESET_DELAY_SECONDS = 30;

    private int tickCounter = 0;

    private Phase phase = Phase.WAITING;
    private int countdownStartTick = 0;
    private ServerBossEvent countdownBar;   // COUNTDOWN 阶段才有，其他阶段为 null
    private int lastBarSecond = -1;         // 上一次写进 Boss 栏的秒数

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
            RunnerTracker.begin(server);   // 快照本局的 runner，用于死亡判定
            GameState.setGameStarted(true, server);
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("游戏开始！"), false);
            notifyExemptPlayers(server);
            phase = Phase.RUNNING;
        }
    }

    /** 游戏进行中：只关心开局时的 runner 是否全部阵亡/淘汰 */
    private void onRunning(MinecraftServer server) {
        for (RunnerTracker.Elimination elimination : RunnerTracker.refresh(server)) {
            UUID id = elimination.id();
            String name = RunnerTracker.nameOf(id);
            TeamManager.removeFromTeam(server, "runners", name);
            CompassTicker.clearRunner(name);
            String reason = elimination.timedOut()
                    ? "掉线超时，视为淘汰"
                    : "已死亡，离开 runners 队伍";
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("跑 " + name + " " + reason), false);
        }

        if (RunnerTracker.isGameOver()) {
            phase = Phase.COUNTDOWN;
            countdownStartTick = tickCounter;
            lastBarSecond = -1;
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("所有 runner 已淘汰，新世界生成倒计时开始"), false);
        }
    }

    /** 倒计时中：Boss 栏显示剩余秒数，归零就重置世界 */
    private void onCountdown(MinecraftServer server) {
        // 兜底：阵亡记录被清掉时取消倒计时，避免误删档
        if (!RunnerTracker.isGameOver()) {
            phase = Phase.RUNNING;
            clearCountdownBar();
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("runner 阵亡记录已清空，倒计时取消"), false);
            return;
        }

        int elapsedTicks = tickCounter - countdownStartTick;
        int secondsLeft = RESET_DELAY_SECONDS - elapsedTicks / 20;

        if (secondsLeft <= 0) {
            clearCountdownBar();
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("正在生成新世界，请稍候..."), false);
            try {
                if (!WorldResetter.reset(server)) {
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal("生成新世界失败，服务器将关闭"), false);
                    server.halt(false);
                    return;
                }
                phase = Phase.WAITING;
            } catch (Throwable t) {
                Manhunt.LOGGER.error("[Manhunt] 生成新世界失败", t);
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("生成新世界失败，服务器将关闭"), false);
                server.halt(false);
            }
            return;
        }

        updateCountdownBar(server, secondsLeft, elapsedTicks);
    }

    /** 创建或更新倒计时 Boss 栏。进度按 tick 平滑变化，文字每秒变一次。 */
    private void updateCountdownBar(MinecraftServer server, int secondsLeft, int elapsedTicks) {
        int totalTicks = RESET_DELAY_SECONDS * 20;
        float progress = Math.max(0.0F, (totalTicks - elapsedTicks) / (float) totalTicks);

        if (countdownBar == null) {
            // 每局用新的 UUID，避免上一局的客户端状态残留
            countdownBar = new ServerBossEvent(UUID.randomUUID(),
                    Component.literal("服务器将在 " + secondsLeft + " 秒后生成新世界"),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
            countdownBar.setProgress(progress);
            countdownBar.setVisible(true);
            lastBarSecond = secondsLeft;
        } else {
            countdownBar.setProgress(progress);
            if (secondsLeft != lastBarSecond) {
                lastBarSecond = secondsLeft;
                countdownBar.setName(
                        Component.literal("服务器将在 " + secondsLeft + " 秒后生成新世界"));
            }
        }

        syncCountdownBar(server);
    }

    /** 让 Boss 栏的接收者始终等于当前在线玩家：中途进服能看到，掉线的不再保留引用。 */
    private void syncCountdownBar(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            countdownBar.addPlayer(player);   // 已在集合里时是空操作
        }
        for (ServerPlayer player : List.copyOf(countdownBar.getPlayers())) {
            if (!online.contains(player.getUUID())) {
                countdownBar.removePlayer(player);
            }
        }
    }

    /** 关掉并丢弃 Boss 栏；先 setVisible(false)，客户端会立即移除。 */
    private void clearCountdownBar() {
        if (countdownBar == null) {
            return;
        }
        countdownBar.setVisible(false);
        countdownBar.removeAllPlayers();
        countdownBar = null;
        lastBarSecond = -1;
    }

    /** 每次服务器启动时调用，清空上一局残留的状态 */
    public void reset() {
        phase = Phase.WAITING;
        tickCounter = 0;
        countdownStartTick = 0;
        clearCountdownBar();
        RunnerTracker.clear();
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

    /**
     * 除被豁免的管理员外，所有在线玩家都必须已经选好队伍。
     *
     * <p>管理员豁免：否则一个挂机、旁观或不打算参与的管理员就会让整局永远开不了，
     * 而且没有任何提示能看出卡在谁身上。
     */
    private static boolean allPlayersHaveTeam(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getTeam() == null && !isTeamExempt(server, player)) {
                return false;
            }
        }
        return true;
    }

    /** 未选队的管理员不参与开局判定。 */
    private static boolean isTeamExempt(MinecraftServer server, ServerPlayer player) {
        return server.getPlayerList().isOp(
                new NameAndId(player.getUUID(), player.getScoreboardName()));
    }

    /** 开局时告诉被豁免的管理员：他们不在本局里。 */
    private static void notifyExemptPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 能走到这里的无队伍玩家只可能是被豁免的管理员
            if (player.getTeam() != null) {
                continue;
            }
            player.sendSystemMessage(
                    Component.literal("你不在任何队伍中，本局不参与（管理员豁免）")
                            .withStyle(ChatFormatting.GRAY));
        }
    }
}
package islogic.manhunt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 记录本局开局时有哪些 runner，以及其中谁已经阵亡。
 *
 * <p>为什么不直接用「runners 队伍是否为空」判断游戏结束：
 * {@link TeamManager#getRunners} 只统计在线玩家，runner 掉线或退出会让队伍
 * 看起来变空，从而被误判为全员阵亡并触发删档重建。改看死亡事件后，
 * 掉线与死亡就能区分开。
 */
public final class RunnerTracker {

    // 开局时在场的 runner
    private static final Set<UUID> startingRunners = new HashSet<>();
    // 其中已经死亡的 runner
    private static final Set<UUID> deadRunners = new HashSet<>();

    private RunnerTracker() {
    }

    /** 开局时调用：快照当前在线的 runner。 */
    public static void begin(MinecraftServer server) {
        clear();
        for (ServerPlayer runner : TeamManager.getRunners(server)) {
            startingRunners.add(runner.getUUID());
        }
    }

    /** runner 死亡时调用；只有开局时在场的 runner 才计入。 */
    public static void markDead(ServerPlayer player) {
        if (startingRunners.contains(player.getUUID())) {
            deadRunners.add(player.getUUID());
        }
    }

    /**
     * 兜底轮询：把在线但已经不活着的开局 runner 记为阵亡。
     * 正常死亡由 {@link RunnerDeathHandler} 的死亡事件登记，这里防止事件被别的模组拦掉。
     */
    public static void refresh(MinecraftServer server) {
        if (startingRunners.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (startingRunners.contains(player.getUUID()) && !player.isAlive()) {
                deadRunners.add(player.getUUID());
            }
        }
    }

    /** 开局时的 runner 是否已经全部阵亡。 */
    public static boolean isGameOver() {
        return !startingRunners.isEmpty() && deadRunners.containsAll(startingRunners);
    }

    public static void clear() {
        startingRunners.clear();
        deadRunners.clear();
    }
}

package islogic.manhunt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 记录本局开局时有哪些 runner，以及其中谁已经阵亡。
 *
 * <p>为什么不直接用「runners 队伍是否为空」判断游戏结束：
 * {@link TeamManager#getRunners} 只统计在线玩家，runner 掉线或退出会让队伍
 * 看起来变空，从而被误判为全员阵亡并触发删档重建。改看死亡事件后，
 * 掉线与死亡就能区分开；掉线超过 {@value #DISCONNECT_TIMEOUT_SECONDS} 秒则视为淘汰。
 */
public final class RunnerTracker {

    /** 掉线多久后视为淘汰。 */
    private static final int DISCONNECT_TIMEOUT_SECONDS = 60;
    private static final int DISCONNECT_TIMEOUT_TICKS = DISCONNECT_TIMEOUT_SECONDS * 20;

    // 开局时在场的 runner
    private static final Set<UUID> startingRunners = new HashSet<>();
    // 其中已经死亡/淘汰的 runner
    private static final Set<UUID> deadRunners = new HashSet<>();
    // 开局 runner 的名字，用于播报
    private static final Map<UUID, String> runnerNames = new HashMap<>();
    // runner 开始掉线的服务器 tick
    private static final Map<UUID, Integer> offlineSinceTick = new HashMap<>();

    private RunnerTracker() {
    }

    /** 开局时调用：快照当前在线的 runner。 */
    public static void begin(MinecraftServer server) {
        clear();
        for (ServerPlayer runner : TeamManager.getRunners(server)) {
            startingRunners.add(runner.getUUID());
            runnerNames.put(runner.getUUID(), runner.getScoreboardName());
        }
    }

    /** runner 死亡时调用；只有开局时在场的 runner 才计入。 */
    public static void markDead(ServerPlayer player) {
        if (startingRunners.contains(player.getUUID())) {
            deadRunners.add(player.getUUID());
            offlineSinceTick.remove(player.getUUID());
        }
    }

    /** 本次新淘汰的 runner；{@code timedOut} 表示掉线超时，否则为死亡兜底。 */
    public record Elimination(UUID id, boolean timedOut) {
    }

    /**
     * 兜底轮询：把在线但已经不活着的开局 runner 记为阵亡；
     * 掉线超过超时时间的 runner 视为淘汰。
     *
     * <p>正常死亡由 {@link RunnerDeathHandler} 的死亡事件登记，这里防止事件被别的模组拦掉。
     *
     * @return 本次新淘汰的 runner（死亡兜底或掉线超时）
     */
    public static List<Elimination> refresh(MinecraftServer server) {
        if (startingRunners.isEmpty()) return List.of();

        List<Elimination> eliminated = new ArrayList<>();

        int now = server.getTickCount();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            online.add(id);
            if (!startingRunners.contains(id)) {
                continue;
            }

            // 重新上线：取消掉线计时
            offlineSinceTick.remove(id);
            if (!player.isAlive() && deadRunners.add(id)) {
                eliminated.add(new Elimination(id, false));
            }
        }

        for (UUID id : startingRunners) {
            if (deadRunners.contains(id) || online.contains(id)) {
                continue;
            }

            int since = offlineSinceTick.computeIfAbsent(id, key -> now);
            if (now - since >= DISCONNECT_TIMEOUT_TICKS) {
                deadRunners.add(id);
                offlineSinceTick.remove(id);
                eliminated.add(new Elimination(id, true));
            }
        }
        return eliminated;
    }

    /** 用 UUID 查开局 runner 的名字，供播报使用。 */
    public static String nameOf(UUID id) {
        return runnerNames.getOrDefault(id, id.toString());
    }

    /** 开局时的 runner 是否已经全部阵亡/淘汰。 */
    public static boolean isGameOver() {
        return !startingRunners.isEmpty() && deadRunners.containsAll(startingRunners);
    }

    public static void clear() {
        startingRunners.clear();
        deadRunners.clear();
        runnerNames.clear();
        offlineSinceTick.clear();
    }
}

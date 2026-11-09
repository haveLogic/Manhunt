package islogic.manhunt;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static islogic.manhunt.TeamManager.getRunners;

public class CompassTicker implements ServerTickEvents.EndTick {
    private static final int UPDATE_INTERVAL = 10; // 每 10 tick 更新一次，减轻负担
    private static final String TRACKER_TAG = "manhunt_tracker";

    private int tickCounter = 0;

    // 结构：跑者名字 -> (维度 -> 该维度中的最后位置)
    // 用名字而不是 UUID：计分板队伍本身按名字记录成员（含离线玩家），
    // 这样每轮都能直接用队伍成员名校验缓存，不依赖玩家执行了哪个命令。
    private static final Map<String, Map<ResourceKey<Level>, Vec3>> lastKnownPositions = new HashMap<>();

    /** 生成新世界后清空缓存，避免指南针指向上一局的位置。 */
    public static void clearCache() {
        lastKnownPositions.clear();
    }

    /** 清理单个 runner 的缓存（死亡/掉线淘汰时调用）。 */
    public static void clearRunner(String runnerName) {
        lastKnownPositions.remove(runnerName);
    }

    /** 创建一个带 Manhunt 标记的追踪指南针。 */
    public static ItemStack createTrackerCompass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        CustomData.update(DataComponents.CUSTOM_DATA, compass,
                tag -> tag.putBoolean(TRACKER_TAG, true));
        return compass;
    }

    /** 只处理由本模组发放的指南针，避免影响玩家自己的普通指南针。 */
    public static boolean isTrackerCompass(ItemStack stack) {
        if (!stack.is(Items.COMPASS)) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(TRACKER_TAG, false);
    }

    @Override
    public void onEndTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL != 0) return;

        Map<String, ServerPlayer> onlineRunners = new HashMap<>();
        for (ServerPlayer runner : getRunners(server)) {
            onlineRunners.put(runner.getScoreboardName(), runner);
        }

        // 兜底：队伍可能被 /team 等命令直接改动，先按当前 runners 队伍成员名
        // 丢弃已经不属于本局的缓存。离线但仍在本局的 runner 名字还在队伍里，缓存会保留。
        int cachedBefore = lastKnownPositions.size();
        lastKnownPositions.keySet().retainAll(TeamManager.getRunnerNames(server));
        if (lastKnownPositions.size() != cachedBefore) {
            Manhunt.LOGGER.info("[Manhunt] 指南针缓存清理：{} -> {} 个跑者",
                    cachedBefore, lastKnownPositions.size());
        }

        // 在线 runner + 掉线但仍保留最后位置的 runner
        Set<String> targets = new HashSet<>(onlineRunners.keySet());
        targets.addAll(lastKnownPositions.keySet());

        // 关键顺序：先用旧缓存更新指南针，再刷新缓存。
        // 目标为空时也要更新：applyTarget(null) 会把指南针里的旧目标清空，
        // 否则它保留着最后一次写入的坐标，看起来永远指向「幽灵位置」。
        // （targets 为空时 findNearestTarget 立即返回 null，额外开销只有背包扫描。）
        for (ServerPlayer holder : server.getPlayerList().getPlayers()) {
            updateCompassFor(holder, targets, onlineRunners);
        }

        // 只刷新在线 runner；掉线但未淘汰的保留最后位置
        for (ServerPlayer runner : onlineRunners.values()) {
            lastKnownPositions
                    .computeIfAbsent(runner.getScoreboardName(), k -> new HashMap<>())
                    .put(runner.level().dimension(), runner.position());
        }
    }

    private Vec3 getLastPosition(String runnerName, ResourceKey<Level> dimension) {
        Map<ResourceKey<Level>, Vec3> dimMap = lastKnownPositions.get(runnerName);
        if (dimMap == null) return null;
        return dimMap.get(dimension);
    }

    private void updateCompassFor(ServerPlayer holder, Set<String> targets,
                                  Map<String, ServerPlayer> onlineRunners) {
        Vec3 targetPos = findNearestTarget(holder, targets, onlineRunners);

        // 指南针可能只放在快捷栏里而没有选中（选中格子为空时也算），
        // 所以不能只看主手/副手，否则指南针在背包里会一直乱转。
        Inventory inventory = holder.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isTrackerCompass(stack)) {
                applyTarget(stack, holder.level(), targetPos);
            }
        }
    }

    /** 找出离 holder 最近的 runner 位置（同维度用实时位置，否则用最后记录的位置）。 */
    private Vec3 findNearestTarget(ServerPlayer holder, Set<String> targets,
                                   Map<String, ServerPlayer> onlineRunners) {
        Vec3 targetPos = null;
        double nearestDist = Double.MAX_VALUE;

        for (String runnerName : targets) {
            if (runnerName.equals(holder.getScoreboardName())) continue;

            ServerPlayer runner = onlineRunners.get(runnerName);
            Vec3 pos;
            if (runner != null
                    && runner.level().dimension().equals(holder.level().dimension())) {
                pos = runner.position();
            } else {
                pos = getLastPosition(runnerName, holder.level().dimension());
                if (pos == null) continue;
            }

            double dist = holder.position().distanceToSqr(pos);
            if (dist < nearestDist) {
                nearestDist = dist;
                targetPos = pos;
            }
        }
        return targetPos;
    }

    private void applyTarget(ItemStack stack, ServerLevel dimension, Vec3 targetPos) {
        if (targetPos == null) {
            stack.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(Optional.empty(), false));
        } else {
            setCompassTarget(stack, dimension, targetPos);
        }
    }

    /**
     * 设置指南针指向指定维度的指定位置
     * @param stack 指南针物品堆
     * @param dimension 目标维度
     * @param pos 目标位置（世界坐标）
     */
    private void setCompassTarget(ItemStack stack, ServerLevel dimension, Vec3 pos) {
        BlockPos blockPos = BlockPos.containing(pos);
        GlobalPos globalPos = new GlobalPos(dimension.dimension(), blockPos);
        LodestoneTracker tracker = new LodestoneTracker(Optional.of(globalPos), false);
        stack.set(DataComponents.LODESTONE_TRACKER, tracker);
    }
}

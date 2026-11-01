package islogic.manhunt;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.UUID;

import static islogic.manhunt.TeamManager.getRunners;

public class CompassTicker implements ServerTickEvents.EndTick {
    private static final int UPDATE_INTERVAL = 5; // 每 5 tick 更新一次，减轻负担
    private static final String TRACKER_TAG = "manhunt_tracker";

    private int tickCounter = 0;

    // 结构：玩家 UUID -> (维度 -> 该维度中的最后位置)
    // 掉线 runner 的缓存要保留，指南针才能指向其最后位置；死亡/淘汰时由外部清理。
    private static final Map<UUID, Map<ResourceKey<Level>, Vec3>> lastKnownPositions = new HashMap<>();

    /** 生成新世界后清空缓存，避免指南针指向上一局的位置。 */
    public static void clearCache() {
        lastKnownPositions.clear();
    }

    /** 清理单个 runner 的缓存（死亡/掉线淘汰时调用）。 */
    public static void clearRunner(UUID runnerId) {
        lastKnownPositions.remove(runnerId);
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

        Map<UUID, ServerPlayer> onlineRunners = new HashMap<>();
        for (ServerPlayer runner : getRunners(server)) {
            onlineRunners.put(runner.getUUID(), runner);
        }

        // 在线 runner + 掉线但仍保留最后位置的 runner
        Set<UUID> targets = new HashSet<>(onlineRunners.keySet());
        targets.addAll(lastKnownPositions.keySet());

        // 关键顺序：先用旧缓存更新指南针，再刷新缓存
        if (!targets.isEmpty()) {
            for (ServerPlayer holder : server.getPlayerList().getPlayers()) {
                updateCompassFor(holder, targets, onlineRunners);
            }
        }

        // 只刷新在线 runner；掉线但未淘汰的保留最后位置
        for (ServerPlayer runner : onlineRunners.values()) {
            lastKnownPositions
                    .computeIfAbsent(runner.getUUID(), k -> new HashMap<>())
                    .put(runner.level().dimension(), runner.position());
        }
    }

    private Vec3 getLastPosition(UUID playerId, ResourceKey<Level> dimension) {
        Map<ResourceKey<Level>, Vec3> dimMap = lastKnownPositions.get(playerId);
        if (dimMap == null) return null;
        return dimMap.get(dimension);
    }

    private void updateCompassFor(ServerPlayer holder, Set<UUID> targets,
                                  Map<UUID, ServerPlayer> onlineRunners) {
        ItemStack stack = holder.getMainHandItem();
        if (!isTrackerCompass(stack)) {
            stack = holder.getOffhandItem();
            if (!isTrackerCompass(stack)) return;
        }

        Vec3 targetPos = null;
        double nearestDist = Double.MAX_VALUE;

        for (UUID runnerId : targets) {
            if (runnerId.equals(holder.getUUID())) continue;

            ServerPlayer runner = onlineRunners.get(runnerId);
            Vec3 pos;
            if (runner != null
                    && runner.level().dimension().equals(holder.level().dimension())) {
                pos = runner.position();
            } else {
                pos = getLastPosition(runnerId, holder.level().dimension());
                if (pos == null) continue;
            }

            double dist = holder.position().distanceToSqr(pos);
            if (dist < nearestDist) {
                nearestDist = dist;
                targetPos = pos;
            }
        }

        if (targetPos == null) {
            stack.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(Optional.empty(), false));
        } else {
            setCompassTarget(stack, holder.level(), targetPos);
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

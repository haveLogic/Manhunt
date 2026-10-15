package islogic.manhunt;

import com.mojang.datafixers.util.Pair;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static islogic.manhunt.TeamManager.getRunners;

public class CompassTicker implements ServerTickEvents.EndTick {
    private static final int UPDATE_INTERVAL = 2; // 每 2 tick 更新一次，减轻负担
    private int tickCounter = 0;

    // 结构：玩家 UUID -> (维度 -> 该维度中的最后位置)
    private final Map<UUID, Map<ResourceKey<Level>, Vec3>> lastKnownPositions = new HashMap<>();

    @Override
    public void onEndTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL != 0) return;

        List<ServerPlayer> runners = getRunners(server);

        // 关键顺序：先用旧缓存更新指南针，再刷新缓存
        if (!runners.isEmpty()) {
            for (ServerPlayer holder : server.getPlayerList().getPlayers()) {
                updateCompassFor(holder, runners);
            }
        }

        // 刷新缓存：记录所有 runner 在当前维度的最新位置
        for (ServerPlayer runner : runners) {
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

    private void updateCompassFor(ServerPlayer holder, List<ServerPlayer> runners) {
        ItemStack stack = holder.getMainHandItem();
        EquipmentSlot slot = EquipmentSlot.MAINHAND;   // ← 这里声明 slot

        if (!stack.is(Items.COMPASS)) {
            stack = holder.getOffhandItem();
            slot = EquipmentSlot.OFFHAND;              // ← 副手时改写 slot
            if (!stack.is(Items.COMPASS)) return;
        }

        ServerPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;
        Vec3 targetPos = null;

        for (ServerPlayer runner : runners) {
            if (runner == holder) continue;

            Vec3 pos;
            if (runner.level().dimension().equals(holder.level().dimension())) {
                pos = runner.position();
            } else {
                pos = getLastPosition(
                        runner.getUUID(),
                        holder.level().dimension()
                );
                if (pos == null) continue;
            }

            double dist = holder.position().distanceToSqr(pos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = runner;
                targetPos = pos;
            }
        }

        if (targetPos == null) {
            stack.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(Optional.empty(), false));
        } else {
            setCompassTarget(stack, holder.level(), targetPos);
        }

        // 关键：把修改后的物品同步给客户端
        //holder.connection.send(new ClientboundSetEquipmentPacket(
        //        holder.getId(),
        //        List.of(Pair.of(slot, stack))
        //));
    }
    /**
     * 设置指南针指向指定维度的指定位置
     * @param stack 指南针物品堆
     * @param dimension 目标维度
     * @param pos 目标位置（世界坐标）
     */
    private void setCompassTarget(ItemStack stack, ServerLevel dimension, Vec3 pos) {
        // 1. 将 Vec3 转换为 BlockPos（指南针以方块为单位追踪）
        BlockPos blockPos = BlockPos.containing(pos);

        // 2. 构建 GlobalPos（维度 + 方块位置）
        GlobalPos globalPos = new GlobalPos(dimension.dimension(), blockPos);

        // 3. 创建 LodestoneTracker 组件
        //    target: Optional.of(globalPos) —— 指定目标位置
        //    tracked: false —— 表示不追踪真实磁石，强制指向该坐标
        LodestoneTracker tracker = new LodestoneTracker(Optional.of(globalPos), false);

        // 4. 将组件写入物品堆
        stack.set(DataComponents.LODESTONE_TRACKER, tracker);
    }
}

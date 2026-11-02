package islogic.manhunt.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 {@link StatsCounter} 内部的统计数据表，用于世界重置时清空玩家统计。
 * 原版没有提供“清空所有统计”的公开方法。
 */
@Mixin(StatsCounter.class)
public interface StatsCounterAccessor {

    @Accessor("stats")
    Object2IntMap<Stat<?>> getRawStats();
}

package islogic.manhunt.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code StructureTemplate$Palette} 用普通 {@link java.util.HashMap} 缓存
 * “某个方块在模板里的所有位置”，但区块生成是多线程的，同一个结构模板会被多个
 * 线程同时使用。重置世界时旧维度的区块生成任务还没跑完，新维度又开始生成，
 * 两边同时调用 {@code Map#computeIfAbsent} 会抛
 * {@link java.util.ConcurrentModificationException}，直接把客户端打崩。
 *
 * <p>这里把缓存换成 {@link ConcurrentHashMap}，保留原版“同一个 key 只计算一次”的
 * 语义，只是让并发访问安全。
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteMixin {

    @Mutable
    @Shadow
    @Final
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void manhunt$useConcurrentCache(CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }
}

package islogic.manhunt.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 读取 MinecraftServer 中生成新世界所必需的私有字段。
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("executor")
    Executor getExecutor();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess getStorageSource();

    @Accessor("savedDataStorage")
    SavedDataStorage getSavedDataStorage();

    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> getLevels();

    @Mutable
    @Accessor("worldGenSettings")
    void setWorldGenSettings(WorldGenSettings settings);
}

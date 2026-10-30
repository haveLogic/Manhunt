package islogic.manhunt;

import islogic.manhunt.mixin.MinecraftServerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 在不关闭服务器进程的情况下，把所有维度（主世界 / 下界 / 末地 / 自定义维度）
 * 替换成全部重新生成的新世界。
 *
 * <p>流程：
 * <ol>
 *     <li>记录每个维度的 LevelStem，并生成新的世界种子</li>
 *     <li>死亡玩家先复活（否则会卡在死亡界面）</li>
 *     <li>把所有玩家临时传送到一个暂存维度</li>
 *     <li>关闭旧主世界、删除区块数据、用新种子重建，并把玩家送进去</li>
 *     <li>对其余所有维度做同样的重建（此时玩家已经在新主世界里）</li>
 * </ol>
 */
public final class WorldResetter {

    private WorldResetter() {
    }

    public static void reset(MinecraftServer server) {
        Manhunt.LOGGER.info("[Manhunt] 游戏结束，开始生成新世界");

        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) server;

        // 1. 快照当前所有维度，并记录它们的 LevelStem（关闭前必须取出来）
        List<ServerLevel> oldLevels = new ArrayList<>();
        server.getAllLevels().forEach(oldLevels::add);

        ServerLevel oldOverworld = server.overworld();
        if (oldOverworld == null) {
            broadcast(server, "生成新世界失败：找不到主世界");
            Manhunt.LOGGER.error("[Manhunt] 找不到主世界，取消重置");
            return;
        }

        Map<ResourceKey<Level>, LevelStem> stems = new HashMap<>();
        for (ServerLevel level : oldLevels) {
            stems.put(level.dimension(), new LevelStem(
                    level.dimensionTypeRegistration(),
                    level.getChunkSource().getGenerator()
            ));
        }

        // 2. 新的世界种子（所有维度共用，和原版一致）
        long newSeed = WorldOptions.randomSeed();
        applyNewSeed(server, serverAccessor, newSeed);

        // 3. 选一个暂存维度。正常情况下用下界/末地；
        //    如果服务器只有主世界，就临时造一个专用维度。
        ServerLevel parkingLevel = null;
        for (ServerLevel level : oldLevels) {
            if (!level.dimension().equals(Level.OVERWORLD)) {
                parkingLevel = level;
                break;
            }
        }
        ServerLevel holdingLevel = null;
        if (parkingLevel == null) {
            holdingLevel = createHoldingLevel(server, serverAccessor, stems.get(Level.OVERWORLD), newSeed);
            parkingLevel = holdingLevel;
        }

        // 4. 死亡玩家先复活，然后所有人移出主世界
        respawnDeadPlayers(server);

        int parkingY = parkingY(parkingLevel);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.teleportTo(parkingLevel, 0.5, parkingY, 0.5, Set.of(),
                    player.getYRot(), player.getXRot(), false);
        }

        // 5. 重建主世界
        rebuildLevel(server, serverAccessor, oldOverworld, stems.get(Level.OVERWORLD),
                newSeed, server.getWorldData().overworldData(), true);
        ServerLevel newOverworld = server.overworld();

        // 6. 挑选新出生点，把玩家送进新主世界
        BlockPos spawnPos = pickSpawn(newOverworld);
        LevelData.RespawnData respawnData =
                LevelData.RespawnData.of(Level.OVERWORLD, spawnPos, 0.0F, 0.0F);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            resetPlayer(player);
            player.teleportTo(newOverworld,
                    spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    Set.of(), player.getYRot(), player.getXRot(), false);
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawnData, false), false);
        }

        // 7. 重建其余所有维度（暂存维度在玩家离开后才会被关闭）
        WorldData worldData = server.getWorldData();
        for (ServerLevel oldLevel : oldLevels) {
            ResourceKey<Level> key = oldLevel.dimension();
            if (key.equals(Level.OVERWORLD)) {
                continue;
            }

            LevelStem stem = stems.get(key);
            if (stem == null) {
                Manhunt.LOGGER.warn("[Manhunt] 跳过维度 {}：找不到 LevelStem", key.identifier());
                continue;
            }

            ServerLevelData levelData = new DerivedLevelData(worldData, worldData.overworldData());
            rebuildLevel(server, serverAccessor, oldLevel, stem, newSeed, levelData, false);        }

        // 8. 清理临时暂存维度
        if (holdingLevel != null) {
            closeAndDelete(server, serverAccessor, holdingLevel);
        }

        // 9. 重置整局游戏的状态
        resetWorldTime(server);
        server.setWeatherParameters(6000, 0, false, false);
        GameState.setGameStarted(false, server);
        TeamManager.clearTeams(server);
        CompassTicker.clearCache();

        broadcast(server, "新世界已生成（所有维度已重置）！使用 /hunters 或 /runners 重新选择队伍");
        Manhunt.LOGGER.info("[Manhunt] 所有维度已重置，新种子: {}", newSeed);
    }

    /** 更新服务器内存和 world_gen_settings.dat 中的种子。 */
    private static void applyNewSeed(MinecraftServer server, MinecraftServerAccessor accessor, long newSeed) {
        WorldGenSettings oldSettings = server.getWorldGenSettings();
        WorldGenSettings newSettings = new WorldGenSettings(
                new WorldOptions(
                        newSeed,
                        oldSettings.options().generateStructures(),
                        oldSettings.options().generateBonusChest()
                ),
                oldSettings.dimensions()
        );

        accessor.setWorldGenSettings(newSettings);
        accessor.getSavedDataStorage().set(WorldGenSettings.TYPE, newSettings);
        accessor.getSavedDataStorage().scheduleSave();
    }

    /** 关闭并删除一个维度的数据，然后用同一个 LevelStem 和新种子重新创建它。 */
    private static void rebuildLevel(MinecraftServer server,
                                     MinecraftServerAccessor accessor,
                                     ServerLevel oldLevel,
                                     LevelStem stem,
                                     long newSeed,
                                     ServerLevelData levelData,
                                     boolean overworld) {
        ResourceKey<Level> key = oldLevel.dimension();

        closeAndDelete(server, accessor, oldLevel);

        List<CustomSpawner> spawners = overworld ? createSpawners(accessor) : List.of();

        ServerLevel newLevel = new ServerLevel(
                server,
                accessor.getExecutor(),
                accessor.getStorageSource(),
                levelData,
                key,
                stem,
                server.getWorldData().isDebugWorld(),
                BiomeManager.obfuscateSeed(newSeed),
                spawners,
                overworld
        );

        accessor.getLevels().put(key, newLevel);
        newLevel.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        server.getPlayerList().addWorldborderListener(newLevel);
    }

    /** 关闭维度并删除它的 region / entities / poi / data。 */
    private static void closeAndDelete(MinecraftServer server,
                                       MinecraftServerAccessor accessor,
                                       ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        accessor.getLevels().remove(key);

        // noSave 避免关闭时又把刚删掉的数据写回去
        level.noSave = true;
        try {
            level.close();
        } catch (IOException e) {
            throw new RuntimeException("关闭维度 " + key.identifier() + " 失败", e);
        }

        Path dimensionPath = accessor.getStorageSource().getDimensionPath(key);
        try {
            deleteContents(dimensionPath);
        } catch (IOException e) {
            throw new RuntimeException("删除维度 " + key.identifier() + " 的数据失败", e);
        }
    }

    /** 服务器没有下界/末地时，临时创建一个用于暂存玩家的维度。 */
    private static ServerLevel createHoldingLevel(MinecraftServer server,
                                                  MinecraftServerAccessor accessor,
                                                  LevelStem overworldStem,
                                                  long seed) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath(Manhunt.MOD_ID, "reset_holding"));

        ServerLevelData levelData = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());

        // 上一次异常退出可能留下残留目录，先清掉
        try {
            deleteContents(accessor.getStorageSource().getDimensionPath(key));
        } catch (IOException e) {
            throw new RuntimeException("清理临时维度失败", e);
        }

        ServerLevel level = new ServerLevel(
                server,
                accessor.getExecutor(),
                accessor.getStorageSource(),
                levelData,
                key,
                overworldStem,
                false,
                BiomeManager.obfuscateSeed(seed),
                List.of(),
                false
        );

        accessor.getLevels().put(key, level);
        level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        server.getPlayerList().addWorldborderListener(level);
        return level;
    }

    private static List<CustomSpawner> createSpawners(MinecraftServerAccessor accessor) {
        return List.of(
                new PhantomSpawner(),
                new PatrolSpawner(),
                new CatSpawner(),
                new VillageSiege(),
                new WanderingTraderSpawner(accessor.getSavedDataStorage())
        );
    }

    /** 死亡玩家还停留在死亡界面，直接传送会导致状态异常，先走原版重生流程。 */
    private static void respawnDeadPlayers(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!player.isAlive()) {
                server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            }
        }
    }

    /** 在维度高度范围内选一个安全的暂存高度（重置过程中不会发生游戏刻，不会摔落）。 */
    private static int parkingY(ServerLevel level) {
        return Math.max(level.getMinY() + 1, Math.min(200, level.getMaxY() - 20));
    }

    /** 与 MinecraftServer#setInitialSpawn 类似：在生成器给出的出生区块附近找一个安全的地表位置。 */
    private static BlockPos pickSpawn(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();

        ChunkPos origin = generator.getOrigin(chunkSource.randomState());
        int y = generator.getSpawnHeight(level);
        if (y < level.getMinY()) {
            y = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    origin.getMinBlockX() + 8, origin.getMinBlockZ() + 8);
        }
        BlockPos guess = new BlockPos(origin.getMinBlockX() + 8, y, origin.getMinBlockZ() + 8);

        // 借助原版逻辑找一个不会被方块卡住、也不在水里的位置
        BlockPos spawnPos;
        try {
            CompletableFuture<Vec3> future = PlayerSpawnFinder.findSpawn(level, guess);
            server.managedBlock(future::isDone);
            spawnPos = BlockPos.containing(future.join());
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 寻找安全出生点失败，使用默认位置", e);
            spawnPos = guess;
        }

        LevelData.RespawnData respawnData =
                LevelData.RespawnData.of(Level.OVERWORLD, spawnPos, 0.0F, 0.0F);

        ServerLevelData overworldData = server.getWorldData().overworldData();
        overworldData.setSpawn(respawnData);
        overworldData.setInitialized(true);
        server.setRespawnData(respawnData);

        return spawnPos;
    }

    private static void resetPlayer(ServerPlayer player) {
        player.getInventory().clearContent();
        player.removeAllEffects();

        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);

        player.setExperienceLevels(0);
        player.setExperiencePoints(0);

        player.setRemainingFireTicks(0);
        player.fallDistance = 0.0;
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** 把新世界的时间拨回白天（时间是否流逝由 ADVANCE_TIME 规则控制）。 */
    private static void resetWorldTime(MinecraftServer server) {
        try {
            Registry<WorldClock> clocks = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
            Holder<WorldClock> overworldClock = clocks.getOrThrow(WorldClocks.OVERWORLD);
            server.clockManager().moveToTimeMarker(overworldClock, ClockTimeMarkers.DAY);
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 重置时间失败", e);
        }
    }

    /** 删除目录下的所有内容，但保留目录本身。 */
    private static void deleteContents(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        List<Path> paths;
        try (Stream<Path> stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }

        for (Path path : paths) {
            if (!path.equals(directory)) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
}

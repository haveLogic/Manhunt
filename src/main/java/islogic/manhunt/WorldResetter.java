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
 *     <li>把所有玩家临时传送到一个专用暂存维度（不借用下界/末地）</li>
 *     <li>关闭旧主世界、删除区块数据、用新种子重建，并把玩家送进去</li>
 *     <li>对其余所有维度做同样的重建（此时玩家已经在新主世界里）</li>
 * </ol>
 *
 * <p>任何一步抛异常时，会先把玩家放回安全位置再抛出，避免把暂存维度里的
 * 高空坐标写进玩家存档。
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

        LevelStem overworldStem = stems.get(Level.OVERWORLD);
        if (overworldStem == null) {
            broadcast(server, "生成新世界失败：找不到主世界的生成参数");
            Manhunt.LOGGER.error("[Manhunt] 找不到主世界 LevelStem，取消重置");
            return;
        }

        ServerLevel holdingLevel = null;
        boolean playersMoved = false;

        try {
            applyNewSeed(server, serverAccessor, newSeed);

            // 3. 玩家只暂存在专用临时维度，不再借用下界/末地
            holdingLevel = createHoldingLevel(server, serverAccessor, overworldStem, newSeed);

            // 4. 死亡玩家先复活，然后所有人移出当前维度
            respawnDeadPlayers(server);

            int parkingY = parkingY(holdingLevel);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.teleportTo(holdingLevel, 0.5, parkingY, 0.5, Set.of(),
                        player.getYRot(), player.getXRot(), false);
                player.fallDistance = 0.0;
                player.setDeltaMovement(Vec3.ZERO);
                playersMoved = true;
            }

            // 5. 重建主世界
            rebuildLevel(server, serverAccessor, oldOverworld, overworldStem,
                    newSeed, server.getWorldData().overworldData(), true);
            ServerLevel newOverworld = server.overworld();
            if (newOverworld == null) {
                throw new IllegalStateException("主世界重建后不存在");
            }

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
                rebuildLevel(server, serverAccessor, oldLevel, stem, newSeed, levelData, false);
            }
        } catch (Throwable t) {
            // 失败兜底：先把玩家挪到安全位置，再清理临时维度
            Manhunt.LOGGER.error("[Manhunt] 重置世界失败", t);
            if (playersMoved) {
                try {
                    ensureOverworldForRescue(server, serverAccessor, stems, newSeed, t);
                    rescuePlayers(server, holdingLevel);
                } catch (Exception e) {
                    Manhunt.LOGGER.error("[Manhunt] 失败兜底本身出错，直接停服", e);
                }
            }
            if (holdingLevel != null) {
                try {
                    closeHoldingQuietly(server, serverAccessor, holdingLevel);
                } catch (Exception e) {
                    Manhunt.LOGGER.warn("[Manhunt] 清理临时维度失败", e);
                }
            }
            throw t instanceof RuntimeException re ? re
                    : new RuntimeException("重置世界失败", t);
        }

        // 8. 清理临时暂存维度
        closeAndDelete(server, serverAccessor, holdingLevel);

        // 9. 重置整局游戏的状态
        resetWorldTime(server);
        server.setWeatherParameters(6000, 0, false, false);
        GameState.setGameStarted(false, server);
        TeamManager.clearTeams(server);
        RunnerTracker.clear();
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
        createLevel(server, accessor, key, stem, newSeed, levelData, overworld);
    }

    /** 用给定参数创建一个维度并注册到服务器；调用方负责先清理旧数据。 */
    private static ServerLevel createLevel(MinecraftServer server,
                                           MinecraftServerAccessor accessor,
                                           ResourceKey<Level> key,
                                           LevelStem stem,
                                           long seed,
                                           ServerLevelData levelData,
                                           boolean overworld) {
        List<CustomSpawner> spawners = overworld ? createSpawners(accessor) : List.of();

        ServerLevel newLevel = new ServerLevel(
                server,
                accessor.getExecutor(),
                accessor.getStorageSource(),
                levelData,
                key,
                stem,
                server.getWorldData().isDebugWorld(),
                BiomeManager.obfuscateSeed(seed),
                spawners,
                overworld
        );

        accessor.getLevels().put(key, newLevel);
        newLevel.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        server.getPlayerList().addWorldborderListener(newLevel);
        return newLevel;
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

        return createLevel(server, accessor, key, overworldStem, seed, levelData, false);
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

    /**
     * 重置失败时把所有玩家挪到安全位置，避免把暂存维度里的高空坐标写进玩家存档。
     * 优先（新）主世界出生点；主世界不可用时退到别的已存在维度，最后才留在暂存维度。
     */
    private static void rescuePlayers(MinecraftServer server, ServerLevel holdingLevel) {
        ServerLevel target = server.overworld();
        if (target == null || server.getLevel(Level.OVERWORLD) != target) {
            target = pickRescueLevel(server, holdingLevel);
        }
        if (target == null) {
            Manhunt.LOGGER.error("[Manhunt] 找不到可用维度，无法安置玩家");
            return;
        }

        BlockPos pos = safeRescuePos(target, target.dimension().equals(Level.OVERWORLD));
        Manhunt.LOGGER.warn("[Manhunt] 把玩家安置到 {} ({}, {}, {})",
                target.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                player.teleportTo(target, pos.getX() + 0.5, pos.getY(),
                        pos.getZ() + 0.5, Set.of(),
                        player.getYRot(), player.getXRot(), false);
            } catch (Exception e) {
                Manhunt.LOGGER.warn("[Manhunt] 传送玩家 {} 失败，改用强制移动",
                        player.getScoreboardName(), e);
                forceMove(player, target, pos);
            }
            player.fallDistance = 0.0;
            player.setDeltaMovement(Vec3.ZERO);

            if (target.dimension().equals(Level.OVERWORLD)) {
                try {
                    LevelData.RespawnData respawn =
                            LevelData.RespawnData.of(Level.OVERWORLD, pos, 0.0F, 0.0F);
                    player.setRespawnPosition(
                            new ServerPlayer.RespawnConfig(respawn, false), false);
                } catch (Exception e) {
                    Manhunt.LOGGER.warn("[Manhunt] 重置玩家 {} 的重生点失败",
                            player.getScoreboardName(), e);
                }
            }
        }
    }

    /** 最后手段：绕过完整传送流程（不触发维度同步包），直接把玩家挂到目标维度。 */
    private static void forceMove(ServerPlayer player, ServerLevel target, BlockPos pos) {
        try {
            player.setServerLevel(target);
            player.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (player.connection != null) {
                player.connection.teleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
            }
        } catch (Exception e) {
            Manhunt.LOGGER.error("[Manhunt] 强制移动玩家 {} 也失败",
                    player.getScoreboardName(), e);
        }
    }

    /** 选一个还能用的维度：优先非主世界、非暂存维度，其次暂存维度。 */
    private static ServerLevel pickRescueLevel(MinecraftServer server, ServerLevel holdingLevel) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level != holdingLevel
                    && server.getLevel(level.dimension()) == level) {
                return level;
            }
        }
        return holdingLevel;
    }

    /** 在主世界用世界出生点，其他维度就地取地表，实在不行才用暂存高度。 */
    private static BlockPos safeRescuePos(ServerLevel level, boolean useSpawn) {
        if (useSpawn) {
            return level.getRespawnData().pos();
        }
        try {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
            if (y > level.getMinY() && y < level.getMaxY()) {
                return new BlockPos(0, y, 0);
            }
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 查询 {} 地表高度失败", level.dimension().identifier(), e);
        }
        return new BlockPos(0, parkingY(level), 0);
    }

    /**
     * 主世界已在重建中被关闭/删除时，先用旧 LevelStem 补一个空主世界，
     * 让玩家至少能回到一个真实存在的维度，而不是留在马上要被删掉的暂存维度。
     */
    private static void ensureOverworldForRescue(MinecraftServer server,
                                                 MinecraftServerAccessor accessor,
                                                 Map<ResourceKey<Level>, LevelStem> stems,
                                                 long newSeed,
                                                 Throwable cause) {
        ServerLevel current = server.overworld();
        if (current != null && server.getLevel(Level.OVERWORLD) == current) {
            return;
        }

        LevelStem stem = stems.get(Level.OVERWORLD);
        if (stem == null) {
            Manhunt.LOGGER.error("[Manhunt] 缺少主世界 LevelStem，无法重建主世界", cause);
            return;
        }

        Path path = accessor.getStorageSource().getDimensionPath(Level.OVERWORLD);
        if (Files.exists(path) && !Files.isWritable(path)) {
            // 目录不可写时区块保存会失败，但内存里仍需要一个主世界，
            // 否则传送玩家时 PlayerList#sendLevelInfo 会因为 overworld() == null 抛异常
            Manhunt.LOGGER.warn("[Manhunt] 主世界目录不可写，仅重建内存中的主世界");
        }

        // 重建前清掉半删除的数据，避免新维度读到残缺的区块
        try {
            deleteContents(path);
        } catch (IOException e) {
            Manhunt.LOGGER.warn("[Manhunt] 清理旧主世界数据失败，仍尝试重建主世界", e);
        }

        try {
            createLevel(server, accessor, Level.OVERWORLD, stem, newSeed,
                    server.getWorldData().overworldData(), true);
            Manhunt.LOGGER.warn("[Manhunt] 已为主世界重建空维度，用于安置玩家");
        } catch (Exception e) {
            Manhunt.LOGGER.error("[Manhunt] 重建主世界失败，玩家将退到其他维度", e);
        }
    }

    /** 失败路径清理临时维度；如果还有玩家留在里面就先不关，避免连玩家一起处理掉。 */
    private static void closeHoldingQuietly(MinecraftServer server,
                                            MinecraftServerAccessor accessor,
                                            ServerLevel holdingLevel) {
        if (server.getLevel(holdingLevel.dimension()) == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == holdingLevel) {
                Manhunt.LOGGER.warn("[Manhunt] 临时维度里仍有玩家，跳过清理");
                return;
            }
        }
        try {
            closeAndDelete(server, accessor, holdingLevel);
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 清理临时维度失败", e);
        }
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

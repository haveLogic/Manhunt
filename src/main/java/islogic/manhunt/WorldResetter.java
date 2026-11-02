package islogic.manhunt;

import islogic.manhunt.mixin.MinecraftServerAccessor;
import islogic.manhunt.mixin.StatsCounterAccessor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.clock.WorldClock;
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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>任何一步失败时，会先把玩家放回安全位置，再返回 {@code false} 由调用方停服，
 * 避免把暂存维度里的高空坐标写进玩家存档。
 */
public final class WorldResetter {

    private WorldResetter() {
    }

    /**
     * 重建所有维度并重置本局状态。
     *
     * @return 是否重置成功；失败时调用方应停止服务器
     */
    public static boolean reset(MinecraftServer server) {
        Manhunt.LOGGER.info("[Manhunt] 游戏结束，开始生成新世界");

        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) server;

        // 1. 快照当前所有维度，并记录它们的 LevelStem（关闭前必须取出来）
        List<ServerLevel> oldLevels = new ArrayList<>();
        server.getAllLevels().forEach(oldLevels::add);

        ServerLevel oldOverworld = server.overworld();
        if (oldOverworld == null) {
            broadcast(server, "生成新世界失败：找不到主世界");
            Manhunt.LOGGER.error("[Manhunt] 找不到主世界，取消重置");
            return false;
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
            return false;
        }

        ServerLevel holdingLevel = null;
        boolean playersMoved = false;
        WorldGenSettings oldSettings = server.getWorldGenSettings();
        boolean overworldRebuilt = false;

        try {
            applyNewSeed(server, serverAccessor, newSeed);

            // 3. 玩家只暂存在专用临时维度，不再借用下界/末地
            holdingLevel = createHoldingLevel(server, serverAccessor, overworldStem, newSeed);

            // 4. 死亡玩家先复活，然后所有人移出当前维度
            respawnDeadPlayers(server);

            int parkingY = parkingY(holdingLevel);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.closeContainer();
                if (!player.teleportTo(holdingLevel, 0.5, parkingY, 0.5, Set.of(),
                        player.getYRot(), player.getXRot(), false)) {
                    throw new IllegalStateException("无法把玩家 " + player.getScoreboardName()
                            + " 移到暂存维度");
                }
                player.fallDistance = 0.0;
                player.setDeltaMovement(Vec3.ZERO);
                playersMoved = true;
            }

            // 5. 重建主世界
            rebuildLevel(server, serverAccessor, oldOverworld, overworldStem,
                    newSeed, server.getWorldData().overworldData(), true);
            overworldRebuilt = true;
            ServerLevel newOverworld = server.overworld();
            if (newOverworld == null) {
                throw new IllegalStateException("主世界重建后不存在");
            }

            // 6. 挑选新出生点，把玩家送进新主世界
            BlockPos spawnPos = pickSpawn(newOverworld);
            LevelData.RespawnData respawnData =
                    LevelData.RespawnData.of(Level.OVERWORLD, spawnPos, 0.0F, 0.0F);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.teleportTo(newOverworld,
                        spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                        Set.of(), player.getYRot(), player.getXRot(), false)) {
                    throw new IllegalStateException("无法把玩家 " + player.getScoreboardName()
                            + " 送到新主世界");
                }
                resetPlayer(player);
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

            // 重建会把主世界挪到 levels 末尾，恢复成主世界优先的顺序
            moveOverworldFirst(serverAccessor);

            // 8. 清理临时暂存维度
            closeHoldingQuietly(server, serverAccessor, holdingLevel);

            // 9. 重置整局游戏的状态
            resetWorldTime(server);
            server.setWeatherParameters(6000, 0, false, false);
            GameState.setGameStarted(false, server);
            TeamManager.clearTeams(server);
            RunnerTracker.clear();
            CompassTicker.clearCache();
            resetAdvancements(server);
            PlayerResetManager.markWorldReset(server);

            broadcast(server, "新世界已生成（所有维度已重置）！使用 /hunters 或 /runners 重新选择队伍");
            Manhunt.LOGGER.info("[Manhunt] 所有维度已重置，新种子: {}", newSeed);
            return true;
        } catch (Throwable t) {
            // 失败兜底：先把玩家挪到安全位置，再清理临时维度
            Manhunt.LOGGER.error("[Manhunt] 重置世界失败", t);

            // 主世界还没重建成功时恢复旧种子，避免磁盘上的旧区块和新种子混用
            if (!overworldRebuilt) {
                try {
                    restoreWorldGenSettings(serverAccessor, oldSettings);
                } catch (Exception e) {
                    Manhunt.LOGGER.error("[Manhunt] 恢复旧世界种子失败", e);
                }
            }

            if (playersMoved) {
                try {
                    ensureOverworldForRescue(server, serverAccessor, stems,
                            server.getWorldGenSettings().options().seed(), t);
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
            return false;
        }
    }

    /** 玩家错过世界重置后重新上线时，补做个人状态重置并送回新主世界出生点。 */
    public static void resetMissedPlayer(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }

        // 死亡状态下线的玩家先恢复生命值，避免登录后停在死亡界面
        if (!player.isAlive()) {
            player.setHealth(player.getMaxHealth());
            player.setRemainingFireTicks(0);
            player.fallDistance = 0.0;
        }

        ServerLevel overworld = server.overworld();
        if (overworld != null) {
            BlockPos spawnPos = overworld.getRespawnData().pos();
            LevelData.RespawnData respawnData =
                    LevelData.RespawnData.of(Level.OVERWORLD, spawnPos, 0.0F, 0.0F);
            try {
                if (!player.teleportTo(overworld,
                        spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                        Set.of(), player.getYRot(), player.getXRot(), false)) {
                    Manhunt.LOGGER.warn("[Manhunt] 补重置时无法传送玩家 {}",
                            player.getScoreboardName());
                }
            } catch (Exception e) {
                Manhunt.LOGGER.warn("[Manhunt] 补重置时传送玩家 {} 失败",
                        player.getScoreboardName(), e);
            }

            try {
                player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawnData, false), false);
            } catch (Exception e) {
                Manhunt.LOGGER.warn("[Manhunt] 补重置时设置玩家 {} 重生点失败",
                        player.getScoreboardName(), e);
            }
        }

        resetPlayer(player);
        player.sendSystemMessage(Component.literal("世界已重置，你的个人状态也已重置"));
        Manhunt.LOGGER.info("[Manhunt] 已补重置错过世界重置的玩家 {}",
                player.getScoreboardName());
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

    /** 重置失败时恢复旧种子，避免旧区块与新种子混用。 */
    private static void restoreWorldGenSettings(MinecraftServerAccessor accessor,
                                                WorldGenSettings oldSettings) {
        accessor.setWorldGenSettings(oldSettings);
        accessor.getSavedDataStorage().set(WorldGenSettings.TYPE, oldSettings);
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

    /** 把主世界放回 levels 的首位，保持原版“主世界优先”的遍历顺序。 */
    private static void moveOverworldFirst(MinecraftServerAccessor accessor) {
        Map<ResourceKey<Level>, ServerLevel> levels = accessor.getLevels();
        ServerLevel overworld = levels.remove(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }

        Map<ResourceKey<Level>, ServerLevel> reordered = new LinkedHashMap<>();
        reordered.put(Level.OVERWORLD, overworld);
        reordered.putAll(levels);
        levels.clear();
        levels.putAll(reordered);
    }

    /** 关闭维度并删除它的 region / entities / poi / data。 */
    private static void closeAndDelete(MinecraftServer server,
                                       MinecraftServerAccessor accessor,
                                       ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        accessor.getLevels().remove(key);

        // 注意：ServerChunkCache#close 仍会保存，noSave 只能阻止后续自动保存，
        // 不能避免关闭时的这次全量写盘。
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

    /**
     * 死亡玩家还停留在死亡界面，直接传送会导致状态异常，先走原版重生流程。
     *
     * <p>不能直接调用 {@code PlayerList#respawn}：那条路径只负责换一个
     * {@link ServerPlayer} 并发送重生包，不会清掉
     * {@code ServerGamePacketListenerImpl#waitingForRespawn}。玩家会一直被视为
     * “等待重生”，之后的移动包全部被忽略、也无法受伤，表现为卡死。
     * 这里模拟客户端点击“重生”时发出的同一个包，让原版完整走一遍
     * 复活 + 重置客户端加载计时。
     */
    private static void respawnDeadPlayers(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.isAlive()) {
                continue;
            }

            ServerGamePacketListenerImpl connection = player.connection;
            if (connection == null) {
                // 没有真实连接（例如测试假人）时不会卡在死亡界面，直接走原版重生
                server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                continue;
            }

            connection.handleClientCommand(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
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
            boolean moved;
            try {
                moved = player.teleportTo(target, pos.getX() + 0.5, pos.getY(),
                        pos.getZ() + 0.5, Set.of(),
                        player.getYRot(), player.getXRot(), false);
            } catch (Exception e) {
                Manhunt.LOGGER.warn("[Manhunt] 传送玩家 {} 失败，改用强制移动",
                        player.getScoreboardName(), e);
                moved = false;
            }
            if (!moved) {
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

    /** 清理临时维度；如果还有玩家留在里面就先不关，避免连玩家一起处理掉。 */
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
        overworldData.setInitialized(true);
        server.setRespawnData(respawnData);

        return spawnPos;
    }

    private static void resetPlayer(ServerPlayer player) {
        player.closeContainer();
        player.getInventory().clearContent();
        player.getInventory().setSelectedSlot(0);
        player.getEnderChestInventory().clearContent();
        player.removeAllEffects();
        player.clearFire();
        player.setRemainingFireTicks(0);
        player.setTicksFrozen(0);
        player.setArrowCount(0);
        player.setAbsorptionAmount(0.0F);
        player.setPortalCooldown(0);
        player.setScore(0);
        player.setLastDeathLocation(Optional.empty());
        player.setShoulderParrotLeft(Optional.empty());
        player.setShoulderParrotRight(Optional.empty());
        player.seenCredits = false;

        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);

        player.totalExperience = 0;
        player.experienceProgress = 0.0F;
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);

        resetRecipes(player);
        resetStatistics(player);

        player.fallDistance = 0.0;
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** 清空玩家的配方书（已解锁配方存在玩家数据里，不在维度目录里）。 */
    private static void resetRecipes(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        try {
            // getRecipes() 包含所有配方，确保配方书里的每一项都能被移除
            player.resetRecipes(server.getRecipeManager().getRecipes());
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 重置玩家 {} 的配方失败",
                    player.getScoreboardName(), e);
        }
    }

    /**
     * 清空统计数据。统计单独保存在 {@code world/players/stats} 里，
     * 内存中的计数器也要一并清空，并把 0 值同步给客户端，刷新统计界面。
     */
    private static void resetStatistics(ServerPlayer player) {
        ServerStatsCounter stats = player.getStats();
        Object2IntMap<Stat<?>> values = ((StatsCounterAccessor) stats).getRawStats();

        if (!values.isEmpty()) {
            // 客户端收到全 0 的统计包后会把对应的旧数据覆盖掉
            Object2IntMap<Stat<?>> zeros = new Object2IntOpenHashMap<>();
            for (Stat<?> stat : values.keySet()) {
                zeros.put(stat, 0);
            }
            values.clear();
            if (player.connection != null) {
                player.connection.send(new ClientboundAwardStatsPacket(zeros));
            }
        }

        // 立即把清空后的统计写回文件，避免下线前崩溃又读回旧数据
        stats.save();
    }

    /**
     * 成就（advancements）存在 {@code world/players/advancements} 下，不在维度目录里，
     * 只重置维度不会清掉。这里删除所有玩家的成就文件，并让在线玩家重新加载，
     * 使内存中的进度和客户端成就界面一起清空。
     */
    private static void resetAdvancements(MinecraftServer server) {
        Path directory = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
        try {
            deleteContents(directory);
        } catch (IOException e) {
            Manhunt.LOGGER.warn("[Manhunt] 清理成就数据失败", e);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                // 文件已删除，reload 会清空进度并从空文件重新加载
                player.getAdvancements().reload(server.getAdvancements());
            } catch (Exception e) {
                Manhunt.LOGGER.warn("[Manhunt] 重置玩家 {} 的成就失败",
                        player.getScoreboardName(), e);
            }
        }
    }

    /** 把新世界的时间拨回 0（时间是否流逝由 ADVANCE_TIME 规则控制）。 */
    private static void resetWorldTime(MinecraftServer server) {
        try {
            Registry<WorldClock> clocks = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
            clocks.listElements().forEach(clock ->
                    server.clockManager().setTotalTicks(clock, 0L));
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

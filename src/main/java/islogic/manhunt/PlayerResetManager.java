package islogic.manhunt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 记录错过世界重置的离线玩家；他们重新上线时补做个人状态重置。
 * 待重置 UUID 保存在世界目录中，服务器重启后仍然有效。
 */
public final class PlayerResetManager {
    private static final String FILE_NAME = "manhunt_pending_resets.txt";
    private static final Set<UUID> pendingReset = new HashSet<>();

    private PlayerResetManager() {
    }

    /** 服务器启动时加载上一次会话遗留的待重置玩家。 */
    public static void load(MinecraftServer server) {
        pendingReset.clear();

        try {
            Path file = pendingFile(server);
            if (!Files.isRegularFile(file)) {
                return;
            }
            for (String line : Files.readAllLines(file)) {
                try {
                    pendingReset.add(UUID.fromString(line.trim()));
                } catch (IllegalArgumentException ignored) {
                    // 忽略损坏的行
                }
            }
            Manhunt.LOGGER.info("[Manhunt] 已加载 {} 个待补重置玩家", pendingReset.size());
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 读取待重置玩家列表失败", e);
        }
    }

    /** 世界重置成功后调用：把所有离线玩家加入待重置列表。 */
    public static void markWorldReset(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
        }

        try {
            Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.forEach(path -> {
                        String name = path.getFileName().toString();
                        if (!name.endsWith(".dat")) {
                            return;
                        }
                        try {
                            UUID id = UUID.fromString(name.substring(0, name.length() - 4));
                            if (!online.contains(id)) {
                                pendingReset.add(id);
                            }
                        } catch (IllegalArgumentException ignored) {
                            // 文件名不是玩家 UUID
                        }
                    });
                }
            }
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 扫描玩家数据失败，部分离线玩家不会补重置", e);
        }
        save(server);
    }

    /** 玩家上线时调用；如果错过重置，补做个人状态重置。 */
    public static void onJoin(ServerPlayer player) {
        if (!pendingReset.remove(player.getUUID())) {
            return;
        }

        try {
            WorldResetter.resetMissedPlayer(player);
        } catch (Exception e) {
            pendingReset.add(player.getUUID());
            Manhunt.LOGGER.error("[Manhunt] 补重置玩家 {} 失败，下次上线重试",
                    player.getScoreboardName(), e);
        }

        MinecraftServer server = player.level().getServer();
        if (server != null) {
            save(server);
        }
    }

    private static Path pendingFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }

    private static void save(MinecraftServer server) {
        Path file = pendingFile(server);
        try {
            if (pendingReset.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                List<String> lines = pendingReset.stream()
                        .map(UUID::toString)
                        .sorted()
                        .toList();
                Files.write(file, lines);
            }
        } catch (Exception e) {
            Manhunt.LOGGER.warn("[Manhunt] 保存待重置玩家列表失败", e);
        }
    }
}

# Manhunt 待修清单

> 整理于 2026-10-28，落盘于 2026-10-29，最后更新于 2026-11-02。按修复优先级排序，修完一条把状态改成 `已修`。
> 行号对应记录时的代码，改动后可能偏移。

## P0 — 必须修

- [x] **runner 掉线被当成全灭，30 秒后删档** — 已修（2026-10-29）
  `TeamManager.getRunners()` 只统计在线玩家，`GameManager.onRunning()` 只判空；
  `onCountdown` 也没有取消机制（有人重新上线不会中止倒计时）。
  - 涉及：`GameManager.java:58-94`、`TeamManager.java:67-89`
  - 方案（已确认）：新增 `RunnerTracker`，开局快照在线 runner 的 UUID，
    `RunnerDeathHandler` 只在死亡事件里登记阵亡；`GameManager.onRunning` 改看
    `RunnerTracker.isGameOver()`；重置/服务器启动时清空记录；README 补充掉线规则。
  - 落地：`RunnerTracker` 在 `onWaiting` 开局时 `begin()`，`RunnerDeathHandler` 登记死亡，
    `GameManager.onRunning` 改看 `isGameOver()`（并加 `refresh()` 轮询兜底：在线但已不活着的
    开局 runner 也记为阵亡），`GameManager.reset()` 与 `WorldResetter.reset()` 均清空记录；
    `onCountdown` 增加「记录被清空则取消倒计时」兜底。
  - 掉线规则（2026-10-31）：掉线 60 秒后视为淘汰，移出 runners 队伍并播报；
    重新上线会取消计时。阈值见 `RunnerTracker.DISCONNECT_TIMEOUT_SECONDS`。
  - 验证（专用服务器 + Carpet 假人）：踢人 20 秒不倒计时、61 秒淘汰；真实伤害死亡 →
    倒计时 → 世界重建成功。
  - 注意：用 `/kill` 杀 **Carpet 假人**不会触发死亡事件 —— Carpet 重写了 `kill(ServerLevel)`，
    直接把假人断线，不走 `LivingEntity#die`。真实玩家 `/kill` 或任何伤害死亡都正常。
    假人测试请用 `/damage <名> 1000 minecraft:generic_kill`。

- [x] **死亡玩家重置时没有真正复活，客户端卡死** — 已修（2026-11-02）
  `WorldResetter.respawnDeadPlayers` 直接调用 `PlayerList#respawn`，绕过了
  `ServerGamePacketListenerImpl#handleClientCommand` 里的
  `restartClientLoadTimerAfterRespawn()`，`waitingForRespawn` 一直为 true：
  服务端会忽略该玩家的移动包、让其无法受伤，客户端表现为卡死。
  - 落地：改为向玩家连接投递 `ServerboundClientCommandPacket(PERFORM_RESPAWN)`，
    走原版“点击重生”同一条流程；没有连接的测试假人仍走 `PlayerList#respawn`。
  - 涉及：`WorldResetter.java:429-445`

- [x] **重置世界时新区块生成崩溃（`ConcurrentModificationException`）** — 已修（2026-11-02）
  重置时旧维度的区块生成任务还没结束（`ChunkMap#close` 只把调度器标记为关闭，
  不会等待正在执行的任务），新维度紧接着开始生成；同一个结构模板被多个线程同时
  调用 `StructureTemplate$Palette#blocks(Block)`，而它的缓存是普通 `HashMap`，
  `computeIfAbsent` 并发修改直接抛 `ConcurrentModificationException`，客户端崩溃。
  - 落地：新增 `StructureTemplatePaletteMixin`，在 `Palette` 构造完成后把 `cache`
    换成 `ConcurrentHashMap`，保留原版“同一个 key 只计算一次”的语义。
  - 涉及：`StructureTemplatePaletteMixin.java`、`manhunt.mixins.json`
  - 验证（专用服务器 + Carpet 假人）：runner 死亡后等待 30 秒重置，
    日志出现「所有维度已重置」，无 `ConcurrentModificationException`。

## P1 — 强烈建议

- [x] **`WorldResetter` 无事务保护** — 已修（2026-10-29，2026-10-31 补充）
  先把玩家丢到 y=200/236 的暂存维度，再删旧维度；异常时 `halt(false)`，
  玩家位置可能被存盘 → 下次进服高空坠落，存档半删。
  - 涉及：`WorldResetter.java:117-121`、`GameManager.java:80-84`
  - 方案（已确认）：玩家只暂存在专用维度 `manhunt:reset_holding`（不再借用下界/末地），
    重置过程包进 try/catch，失败时先把玩家放回安全位置，再由 `GameManager` 停服。
  - 落地：`reset()` = 「尝试块 + catch 兜底」；`ensureOverworldForRescue()` 在主世界已从
    `levels` 移除时用旧 `LevelStem` 重建一个（目录不可写时至少建内存维度，否则传送会因
    `overworld()` 为 null 而空指针）；`rescuePlayers()` 依次尝试新主世界出生点 → 其他已存在维度
    → 暂存维度，传送失败再退回 `setServerLevel` + `snapTo` 强制移动，安置成功后同步重生点；
    最后 `closeHoldingQuietly()` 清理暂存维度（里面还有人就跳过并告警）。
  - 2026-10-31：`reset()` 改为返回 `boolean`，早期失败与异常路径均返回 false；
    成功路径的清理和状态重置也放进 try；`GameManager` 收到 false 后播报并停服。
    步骤 4/6 的 `teleportTo` 返回值已检查，失败会进入 rescue 流程。
  - 验证（专用服务器 + Carpet 假人）：地狱内死亡 → 倒计时 → 重置成功；
    把 `dimensions/minecraft/overworld` 改成只读制造 `AccessDeniedException` →
    日志出现「重置世界失败」「主世界目录不可写，仅重建内存中的主世界」
    「已为主世界重建空维度」「把玩家安置到 minecraft:overworld」；
    停服后 `players/data/*.dat` 为 `Dimension=minecraft:overworld`、
    位置在出生点（不再是 `manhunt:reset_holding` + y=200），临时维度目录已清空。

## P2 — 中危，改动量小

- [x] **`pickSpawn` 导致 `server.setRespawnData(...)` 提前 return** — 已修（2026-10-31）
  先 `overworldData.setSpawn(...)` 使 `effectiveRespawnData` 不更新、默认出生点包不广播。
  修法：删掉前者。
  - 涉及：`WorldResetter.java:335-337`
- [x] **重置前未 `player.closeContainer()`** — 已修（2026-10-31）
  开着的箱子/工作台会引用旧维度的方块实体。
  - 落地：移入暂存维度前和 `resetPlayer()` 里都先关闭容器。
- [x] **末影箱内容会带进新世界** — 已修（2026-10-31）
  `resetPlayer` 未清末影箱。
  - 涉及：`WorldResetter.java:342-356`
  - 落地：`resetPlayer()` 调用 `getEnderChestInventory().clearContent()`。
- [x] **游戏进行中猎人丢失指南针后无法补领** — 已修（2026-11-01）
  `/hunters` 在游戏开始后无条件拒绝，连已在 hunters 队伍的重复执行也被挡；
  指南针只在入队时发放，死亡掉落或丢失后整局无法再获得。
  - 落地：只有当前队伍与目标队伍不同才拒绝；同队重复执行返回成功并补发指南针。
  - 涉及：`Commands.java:40-56`
- [x] **重置失败时新种子已落盘** — 已修（2026-11-01）
  `applyNewSeed()` 在删除旧维度前就写入世界种子；失败停服后旧区块会与新种子混用。
  - 落地：`reset()` 记录旧 `WorldGenSettings`，主世界重建成功前失败则恢复旧种子；
    救援主世界使用恢复后的当前种子。
  - 涉及：`WorldResetter.java:116-117`、`WorldResetter.java:207-214`、`WorldResetter.java:298-303`

## P3 — 清理

- [x] `noSave = true` 实际无效（`ServerChunkCache.close()` 无条件 `save(true)`），
      注释误导，重置时会全量写盘再删除。 — 已修注释（2026-10-31）
- [x] `WorldResetter.java:155-156` 少换行。 — 已整理（2026-10-31）
- [x] `GameState.setGameStarted(false)` 的短路会让 `ADVANCE_TIME` 偶尔不被重新冻结。 — 已修（2026-10-31）
      去掉提前 return，状态与规则值始终同步。
- [x] `CompassTicker` 死代码（`nearest`、`slot`、注释掉的装备包同步），
      且死亡 runner 的缓存不清。 — 已修（2026-10-31）
      删除死代码；死亡/掉线淘汰时清理该 runner 缓存，掉线但未淘汰的缓存继续保留。
- [x] `Commands` 的 `/runners` 会删掉玩家所有指南针。 — 已修（2026-10-31）
      追踪指南针写入 `manhunt_tracker` 自定义数据，只发放/删除/更新带标记的指南针。
- [x] 每次重置累积 worldborder 监听器；`levels` 中主世界顺序被挪到末尾。 — 已核实并处理（2026-10-31）
      worldborder 监听器挂在各维度自己的 `WorldBorder` 上，旧维度关闭后随之丢弃，不会跨局累积；
      `levels` 顺序在重置后显式把主世界放回首位。
- [x] 模板残留：`ExampleMixin` / `ExampleClientMixin`、`fabric.mod.json` 占位符。 — 已清理（2026-10-31）
- [x] 构建配置：Fabric 依赖改用 `modImplementation`；`fabric_api_version` 对齐运行版本
      `0.165.0+26.3`；wrapper 下载源改回官方并增加重试。 — 已修（2026-10-31）
- [x] **`RunnerTracker.refresh()` 死亡兜底未清理队伍/指南针/未播报** — 已修（2026-11-01）
  现在返回本次新淘汰的 runner（区分死亡兜底/掉线超时），由 `GameManager` 统一移出队伍、
  清理指南针缓存并播报；正常死亡事件已登记过则不会重复处理。
- [x] **离线补重置可能让死亡状态下线的玩家停在死亡界面** — 已修（2026-11-01）
  `resetMissedPlayer()` 先恢复生命值、灭火并清零摔落距离，再传送和重置。
- [x] **游戏中加入的欢迎语提示了会被拒绝的命令** — 已修（2026-11-01）
  `PlayerJoinHandler` 按 `GameState` 显示“游戏进行中”或选队提示。

## 已确认正常

新种子落盘、三/自定义维度重建参数、末地龙重新初始化、出生点安全、
指南针组件自动同步、`END_SERVER_TICK` 阶段替换 `levels` 无并发问题
（结构模板缓存另见上方 2026-11-02 的 `ConcurrentModificationException` 条目）。

## 补充说明

- 离线玩家：重置成功后会扫描 `PLAYER_DATA_DIR`，把不在线的玩家 UUID 写入
  世界目录的 `manhunt_pending_resets.txt`；这些玩家下次上线时由 `PlayerResetManager`
  补做个人状态重置并传送到新主世界出生点。若该文件无法写入或被手动删除，
  对应玩家只能保留旧状态。

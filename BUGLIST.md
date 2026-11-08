# Manhunt 待修清单

> 整理于 2026-10-28，落盘于 2026-10-29，最后更新于 2026-11-07。按修复优先级排序，修完一条把状态改成 `已修`。
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
  - 掉线规则（2026-10-31，2026-11-07 阈值改为 3 分钟）：掉线 3 分钟（180 秒）后视为淘汰，
    移出 runners 队伍并播报；3 分钟内重新上线会取消计时。阈值见
    `RunnerTracker.DISCONNECT_TIMEOUT_SECONDS`。
  - 验证（专用服务器 + Carpet 假人，当时阈值 60 秒）：踢人 20 秒不倒计时、61 秒淘汰；
    真实伤害死亡 → 倒计时 → 世界重建成功。（2026-11-07 阈值改为 180 秒，需按新阈值复测。）
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

- [x] **管理员没选队导致整局无法开始** — 已修（2026-11-07）
  `GameManager.allPlayersHaveTeam` 要求所有在线玩家都在队伍里；任何挂机、旁观或不打算
  参与的管理员都会让开局条件永远不成立，且没有任何提示能看出卡在谁身上。
  - 落地：新增 `isTeamExempt()`，OP（`PlayerList#isOp(NameAndId)`）没选队也不阻塞开局；
    开局时给这类管理员单独发一条「本局不参与」提示。
  - 涉及：`GameManager.java` 的 `allPlayersHaveTeam` / `notifyExemptPlayers`
  - 注意：被豁免的管理员仍以普通身份留在世界里，能攻击 runner；需要更严格时在
    `notifyExemptPlayers` 里改成切旁观模式。

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
- [x] **重置世界后成就没有重置** — 已修（2026-11-02）
  成就数据保存在 `world/players/advancements/<uuid>.json`，不在维度目录里，
  `WorldResetter` 只删除维度数据，成就因此一直保留。
  - 落地：重置时删除成就目录下所有文件；在线玩家再调用
    `PlayerAdvancements#reload`，让内存进度和客户端成就界面一起清空。
    离线玩家下次上线会直接从空文件加载。
  - 涉及：`WorldResetter.java:725-743`
  - 验证（专用服务器 + Carpet 假人）：重置前给假人授予 `minecraft:story/mine_stone`，
    重置后成就文件中不再包含该成就。

- [x] **重置世界后时间没有归零** — 已修（2026-11-02）
  `resetWorldTime` 原来调用 `moveToTimeMarker(..., Day)`，新世界从早上开始而不是 0。
  - 落地：遍历 `WORLD_CLOCK` 注册表里的所有时钟，调用
    `ServerClockManager#setTotalTicks(clock, 0L)` 统一归零；该方法会同步客户端
    并刷新环境属性缓存。
  - 涉及：`WorldResetter.java:745-753`

- [x] **重置世界后玩家配方和统计数据残留** — 已修（2026-11-02）
  配方书保存在玩家 `.dat`，统计保存在 `world/players/stats/<uuid>.json`，
  只清背包/末影箱不会重置。另外经验总值、已选快捷栏、传送冷却、死亡点和
  肩上的鹦鹉也属于玩家个人数据。
  - 落地：`resetPlayer` 现在统一重置
    - 配方：`ServerPlayer#resetRecipes(RecipeManager#getRecipes())`
    - 统计：新增 `StatsCounterAccessor` 清空内存统计，向客户端发送全 0 的
      `ClientboundAwardStatsPacket`，并立即 `save()` 写回文件
    - 经验总值 / 进度、快捷栏选中项、传送冷却、分数、死亡点、肩上的鹦鹉、片尾观看标记
  - 离线玩家仍通过 `PlayerResetManager` 的补重置路径调用同一个 `resetPlayer`。
  - 涉及：`WorldResetter.java:647-723`、`StatsCounterAccessor.java`、`manhunt.mixins.json`

- [x] **指南针放在快捷栏但未选中时不更新，看起来像“改间隔后失效”** — 已修（2026-11-02）
  `CompassTicker.updateCompassFor` 之前只处理主手/副手；而 `/hunters` 把指南针放进第一个空格，
  不一定会选中它。此时服务端永远不会给这枚指南针写入 `LODESTONE_TRACKER`，
  客户端只能显示“无目标乱转”，和更新间隔（5 tick / 10 tick）无关。
  - 落地：先算出离该玩家最近的 runner 目标，再遍历整个玩家背包
    （快捷栏、主背包、护甲、副手）里所有带 `manhunt_tracker` 标记的指南针，统一写入目标。
  - 涉及：`CompassTicker.java:99-160`
  - 验证（专用服务器 + Carpet 假人，`UPDATE_INTERVAL = 10`）：
    指南针放在 `hotbar.5`、选中格为 0，runner 传送到 (210,100,210) 后存盘，
    玩家 `.dat` 中的指南针带有 `minecraft:lodestone_tracker`，目标为 runner 的实时位置。

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

- [x] **掉线淘汰阈值改为 3 分钟，并说明超时不可撤销** — 已修（2026-11-07）
  审查项 P3-13：README 写「掉线超过 60 秒视为淘汰；期间重新上线则继续游戏」，
  但超时一旦写入 `deadRunners` 就无法撤销，倒计时期间重新上线也不会中止重置。
  - 落地：`DISCONNECT_TIMEOUT_SECONDS` 由 `60` 改为 `180`（3 分钟 / 3600 tick）；
    README 阈值与措辞同步，并补「超时淘汰不可撤销」说明。
  - 涉及：`RunnerTracker.java:25`、`README.md:17`、`BUGLIST.md`

## 行为调整

- **游戏进行中允许无队伍玩家加入猎人**（2026-11-08）
  原本开局后所有队伍变更都被拒绝，中途进服的玩家只能等到下一局。
  - 现在：游戏中无队伍玩家执行 `/hunters` 可中途加入并领取指南针，全服播报；
    `/runners` 和「已在本局队伍里换队」仍然拒绝。
  - 跑者不受影响：仍在 runners 队伍里的玩家转不了猎人，`RunnerTracker` 的开局快照
    与胜负判定不变。
  - 涉及：`Commands.java`、`PlayerJoinHandler.java`、`README.md`

## 已确认正常

新种子落盘、三/自定义维度重建参数、末地龙重新初始化、出生点安全、
指南针组件通过容器菜单自动同步（只有被 `CompassTicker` 写入过的格子才会变，见上方条目）、
`END_SERVER_TICK` 阶段替换 `levels` 无并发问题
（结构模板缓存另见上方 2026-11-02 的 `ConcurrentModificationException` 条目）。

## 补充说明

- 离线玩家：重置成功后会扫描 `PLAYER_DATA_DIR`，把不在线的玩家 UUID 写入
  世界目录的 `manhunt_pending_resets.txt`；这些玩家下次上线时由 `PlayerResetManager`
  补做个人状态重置并传送到新主世界出生点。若该文件无法写入或被手动删除，
  对应玩家只能保留旧状态。

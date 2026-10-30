# Manhunt 待修清单

> 整理于 2026-10-28，落盘于 2026-10-29。按修复优先级排序，修完一条把状态改成 `已修`。
> 行号对应记录时的代码，改动后可能偏移。

## P0 — 必须修

- [x] **runner 掉线被当成全灭，30 秒后删档** — 已修（2026-10-29）
  `TeamManager.getRunners()` 只统计在线玩家，`GameManager.onRunning()` 只判空；
  `onCountdown` 也没有取消机制（有人重新上线不会中止倒计时）。
  - 涉及：`GameManager.java:58-94`、`TeamManager.java:67-89`
  - 方案（已确认）：新增 `RunnerTracker`，开局快照在线 runner 的 UUID，
    `RunnerDeathHandler` 只在死亡事件里登记阵亡；`GameManager.onRunning` 改看
    `RunnerTracker.isGameOver()`；重置/服务器启动时清空记录；README 补一句「掉线不算死亡」。
  - 落地：`RunnerTracker` 在 `onWaiting` 开局时 `begin()`，`RunnerDeathHandler` 登记死亡，
    `GameManager.onRunning` 改看 `isGameOver()`（并加 `refresh()` 轮询兜底：在线但已不活着的
    开局 runner 也记为阵亡），`GameManager.reset()` 与 `WorldResetter.reset()` 均清空记录；
    `onCountdown` 增加「记录被清空则取消倒计时」兜底。
  - 验证（专用服务器 + Carpet 假人）：踢人 20 秒不倒计时；真实伤害死亡 → 倒计时 → 世界重建成功。
  - 注意：用 `/kill` 杀 **Carpet 假人**不会触发死亡事件 —— Carpet 重写了 `kill(ServerLevel)`，
    直接把假人断线，不走 `LivingEntity#die`。真实玩家 `/kill` 或任何伤害死亡都正常。
    假人测试请用 `/damage <名> 1000 minecraft:generic_kill`。

## P1 — 强烈建议

- [x] **`WorldResetter` 无事务保护** — 已修（2026-10-29）
  先把玩家丢到 y=200/236 的暂存维度，再删旧维度；异常时 `halt(false)`，
  玩家位置可能被存盘 → 下次进服高空坠落，存档半删。
  - 涉及：`WorldResetter.java:117-121`、`GameManager.java:80-84`
  - 方案（已确认）：玩家只暂存在专用维度 `manhunt:reset_holding`（不再借用下界/末地），
    重置过程包进 try/catch，失败时先把玩家放回安全位置，再把异常抛给 `GameManager` 停服。
  - 落地：`reset()` = 「尝试块 + catch 兜底」；`ensureOverworldForRescue()` 在主世界已从
    `levels` 移除时用旧 `LevelStem` 重建一个（目录不可写时至少建内存维度，否则传送会因
    `overworld() == null` 空指针）；`rescuePlayers()` 依次尝试新主世界出生点 → 其他已存在维度
    → 暂存维度，传送失败再退回 `setServerLevel` + `snapTo` 强制移动，安置成功后同步重生点；
    最后 `closeHoldingQuietly()` 清理暂存维度（里面还有人就跳过并告警）。
  - 验证（专用服务器 + Carpet 假人）：地狱内死亡 → 倒计时 → 重置成功；
    把 `dimensions/minecraft/overworld` 改成只读制造 `AccessDeniedException` →
    日志出现「重置世界失败」「主世界目录不可写，仅重建内存中的主世界」
    「已为主世界重建空维度」「把玩家安置到 minecraft:overworld」；
    停服后 `players/data/*.dat` 为 `Dimension=minecraft:overworld`、
    位置在出生点（不再是 `manhunt:reset_holding` + y=200），临时维度目录已清空。

## P2 — 中危，改动量小

- [ ] **`pickSpawn` 导致 `server.setRespawnData(...)` 提前 return**
  先 `overworldData.setSpawn(...)` 使 `effectiveRespawnData` 不更新、默认出生点包不广播。
  修法：删掉前者。
  - 涉及：`WorldResetter.java:335-337`
- [ ] **重置前未 `player.closeContainer()`**
  开着的箱子/工作台会引用旧维度的方块实体。
- [ ] **末影箱内容会带进新世界**
  `resetPlayer` 未清末影箱。
  - 涉及：`WorldResetter.java:342-356`

## P3 — 清理

- [ ] `noSave = true` 实际无效（`ServerChunkCache.close()` 无条件 `save(true)`），
      注释误导，重置时会全量写盘再删除。
- [ ] `WorldResetter.java:155-156` 少换行。
- [ ] `GameState.setGameStarted(false)` 的短路会让 `ADVANCE_TIME` 偶尔不被重新冻结。
- [ ] `CompassTicker` 死代码（`nearest`、`slot`、注释掉的装备包同步），
      且死亡 runner 的缓存不清。
- [ ] `Commands` 的 `/runners` 会删掉玩家所有指南针。
- [ ] 每次重置累积 worldborder 监听器；`levels` 中主世界顺序被挪到末尾。

## 已确认正常

新种子落盘、三/自定义维度重建参数、末地龙重新初始化、出生点安全、
指南针组件自动同步、`END_SERVER_TICK` 阶段替换 `levels` 无并发问题。

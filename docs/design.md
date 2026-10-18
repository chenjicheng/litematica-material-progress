# 材料区收集进度：设计

本文说明这个模组要解决什么问题、为什么这样实现，以及哪些结论经过实测验证。读者是需要维护或审查实现的人。

文中所有 Minecraft / Fabric API 名称按 **1.21.11** 核对，使用本项目的 yarn 映射；必要时在括号内给出上游 mojang 映射名。

## 1. 目标与约束

模组为共享原理图（Litematica + Syncmatica）划定一块**材料区**，由服务端统计区内容器里的材料，
在游戏内菜单显示原理图所需材料的**收集进度**。

以下约束来自用户，违反任何一条即方案错误：

1. 扫描只在服务端进行，客户端不发起逐箱 NBT 请求。
2. 服务端维护权威数据，客户端只获取和接收。
3. 扫描异步分片，不抬高 MSPT，可取消、可恢复。
4. 「已收集」只算材料区内的容器，不含玩家背包，也不含已放置的方块。
5. 一个材料区对应一个 placement。只统计箱子类容器（箱子、陷阱箱、木桶），并递归统计箱内的潜影盒与收纳袋。
   末影箱、漏斗、发射器、矿车不计。
6. 所需材料按**全量原理图**统计。客户端必须标注这一点：玩家在 Litematica 里禁用子区域后，
   服务端算出的所需会大于其界面所示，不说明就会被当成缺陷。
7. 进度同时显示两个维度：完成种类/总种类，已收集数/所需数。
8. 完全重实现，不复制上游代码（LGPL-3.0 只允许链接与继承）。

## 2. 架构

模组是独立的 Fabric 附加模组，`environment: "*"`。服务端与客户端的分工如下：

| 层 | 职责 | 关键实现 |
| --- | --- | --- |
| 服务端扫描器 | 分片扫描材料区内的容器并聚合 | `ServerTickEvents.END_SERVER_TICK` 驱动，自适应每 tick 预算 |
| 服务端状态 | 保存材料区定义、进度快照与版本号 | 每世界一个 JSON 文件，原子写入 |
| 所需材料 | 解析 Syncmatica 服务端保存的 `.litematic` | 服务端自行解析，不依赖客户端 |
| 协议 | 客户端请求，服务端下发快照与状态 | 自有命名空间 `lmp` |
| 客户端 | 显示进度，录入区域 | 复用 Litematica 的 `GuiMaterialList` |

**为什么不复用上游的 payload 频道**：这是已核实的限制，不是风格选择。

- Fabric 对每个 payload id 只允许一个全局接收器。`registerGlobalReceiver` 内部用 `putIfAbsent`，
  重复注册直接返回 `false`；`PayloadTypeRegistry.register` 重复注册抛异常。因此 `servux:litematics` 无法共享。
- Syncmatica 有 4 个 `handleCustomPayload` mixin，会把 `syncmatica` 命名空间下的任何 payload
  无条件强转为 `SyncmaticaPacket.Payload`。外来 payload 会 `ClassCastException`。

因此本模组使用自己的 `lmp` 命名空间。

## 3. 服务端扫描器

### 3.1 扫描如何推进

`ServerTickEvents.END_SERVER_TICK` 每 tick 调用一次 `ScanScheduler.tick(server)`。
同一时刻只允许一个扫描，取消通过 volatile 标志，游标可恢复：区块列表索引加单个区块内的 `List<BlockPos>` 快照。

区块访问只用 `ServerChunkManager.getWorldChunk(cx, cz)`（mojang 映射为 `getChunkNow`）。
它不强制加载：区块未加载或在非服务端线程上调用时返回 `null`。返回 `null` 时扫描器跳过该区块、
把它记为「跳过」并前进游标。

进入一个区块时，扫描器先把 `WorldChunk.getBlockEntities().keySet()` 快照成 `List<BlockPos>`。
`BlockPos` 不可变，可以安全跨 tick 保留。恢复时按索引取位置，再用 `WorldChunk.getBlockEntity(pos)`
取方块实体，并重新判定类型。

### 3.2 如何守住 tick 预算

每 tick 的预算是 `min(2ms, 50ms - server.getAverageTickTime() - 5ms)`；余额不足时整片跳过，
等服务器空闲再继续。

预算检查放在**每个方块实体循环的顶部**。只在容器处检查会冲过头：一个全是告示牌、床或刷怪笼的区块
不会触发「容器上限」，但同样要花时间遍历。

以下调用会强制加载区块或阻塞 tick 线程，禁止使用：

- `World.getBlockEntity(pos)`：内部走 `getBlockEntity(pos, CreationType.IMMEDIATE)`，可能在 tick 线程上强制加载。
- `ServerChunkManager.getChunk(x, z, ChunkStatus.FULL, true)`：阻塞等待磁盘 I/O。
- 跨 tick 持有 `WorldChunk` 或 `ItemStack` 引用。

线程模型受同一个限制约束。`ItemStack` 非线程安全（可变的 `count` 与 `MergedComponentMap`），
读取世界与容器只能在服务端主线程。可以离开主线程的只有纯快照：排序、组包，以及 `.litematic` 的 NBT 解析。

### 3.3 实测结果

`LmpScanCostGameTest` 在真实专用服务端上测量扫描开销。它保住 256 个区块（等于 `MAX_CHUNKS`）的加载票，
放入 16384 个有内容的箱子（每区块 64 个），静置后让生产路径真正跑起来：2 轮预热加 20 轮统计，逐 tick 采样。

| 指标 | 实测 |
| --- | --- |
| 扫描 tick（`scheduler.tick()` 耗时） | 均值 2.01ms，p50 2.00ms，p95 2.02ms，p99 **2.03ms**，最大 2.04ms（n≈70） |
| 收尾 tick（最后一轮扫描加落库、发布） | 2.1–7.8ms |
| 每个容器 | 约 0.4µs |
| 已加载区块数 | 前后完全不变，确认扫描不加载区块 |
| 覆盖率与快照内容 | 1000‰；1048576 个钻石、16384 个空潜影盒，逐项精确匹配 |
| JIT 预热 | 第一个扫描 tick 为 8–14ms，一次性 |

结论：`MAX_SLICE_MS = 2.0` 的预算成立，p99 恰好压在预算上；覆盖 256 个区块的区域约 3 到 5 个 tick 扫完。
四个常量都不需要调整。

这次测量还暴露了一个缺陷，已修复。收尾那一 tick 原本会同步调用 `store.put()` 再 `save()` 写盘，
实测把该 tick 顶到 **19.5ms**，接近半个 tick。现在扫描收尾只更新内存
（`RegionStore.updateInMemory`，且只更新已存在的区域），持久化交给区域增删改和服务器停止时的 `save()`。
收尾 tick 降到 2.1–7.8ms。代价是 `lastScanAt` 与 `coveragePermille` 不再每次扫描落盘，重启后界面显示
「尚未扫描」。完成态快照本来也不持久化（见 LMP-6），重启后必然重扫。

### 3.4 容易踩错的语义

**容器判定**：`be instanceof net.minecraft.inventory.Inventory`（mojang 映射为 `Container`）
覆盖全部原版容器方块实体。本模组按约束 5 再收窄为箱子类白名单。

**双箱**：只统计每个 `ChestBlockEntity` 自己的 27 格 Inventory。不要用 `ChestBlock.getInventory(...)`，
它返回覆盖两半的 `DoubleInventory`，会把数量翻倍。

**递归与计数**：`DataComponentTypes.CONTAINER` 走 `ContainerComponent.iterateNonEmpty()`，
`BUNDLE_CONTENTS` 走 `BundleContentsComponent.iterate()`。组件缺失或为空时，容器物品本身计 1，
与 Litematica 一致；组件非空时，内容物数量乘以 `stack.getCount()`。

参考实现 `chenjicheng/gg` 的 `ItemCountManager.countRecursive` 在组件存在时直接返回，
会把空潜影盒和空收纳袋算成 0，递归时也不乘 `stack.getCount()`。本模组重写了这段逻辑，
并用单元测试覆盖空潜影盒、堆叠潜影盒、收纳袋和双箱不翻倍。

**取消的触发点**：服务器停止（`ScanScheduler.cancel()`），以及区域被删除（`cancelRegion(id)`，
只取消该区域自己的扫描）。被新任务取代的请求不会排队，因为调度器同一时刻只允许一个扫描。

**取消的语义是「结果作废」，不是「提前收工」**。`RegionScanner.advance` 在被取消时同样返回 true，
表示不必再推进；调度器必须用 `isCancelled()` 区分「扫完了」和「被取消了」。

被取消的计数是半成品。如果照常走完成路径，`store.put(...)` 会把刚刚删除的区域写回存储：
玩家执行 `/lmp region delete` 之后区域又出现，而删除动作已经落盘，属于静默的数据复活。
因此取消只清状态、发一次 IDLE、丢弃结果。删除命令用 `cancelRegion(id)` 而不是 `cancel()`，
后者会打断另一个无关区域的扫描。

**覆盖率只认「走完」**：区块取到方块实体位置快照时不算已扫描，只有把该区块的位置全部走完才算。
取了快照却中途丢下（例如跨 tick 后区块被卸载）记为「跳过」。否则会出现覆盖率 1000‰、状态 COMPLETE，
但整整一片容器从未被统计的情况，这比报告「不完整」更危险。

## 4. 所需材料

服务端的 `LitematicRequiredMaterialSource` 串起三个类，由 `LmpServer.initialize()` 在服务器启动前接入。

| 类 | 职责 |
| --- | --- |
| `SchematicLocator` | 把 placementId 解析成 `<gameDir>/syncmatics/<hash>.litematic`；Syncmatica 的类引用隔离在私有嵌套类里，未安装时不加载 |
| `LitematicReader` | 纯 NBT 解析，不访问世界，可以离开 tick 线程；输出 `Litematic{formatVersion, dataVersion, regions}` |
| `RequiredMaterialCalculator` | 把方块状态折算成物品数量；语义对齐 Litematica 的 `MaterialCache` 与 `MaterialListUtils` |

路径链路：`placementId --getPlacement--> ServerPlacement.getHash() --> <gameDir>/syncmatics/<hash>.litematic`。
文件名是**内容哈希**，既不是显示名，也不是 placement id。

### 4.1 文件格式

以下要点已逐项对照上游核实。

- 文件是 GZIP 压缩的未命名根 Compound，顶层键为 `Version`（格式版本，取值 1 到 7）、
  `MinecraftDataVersion`、`Metadata` 和 `Regions`。读取用
  `NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes())`；1.21.11 没有单参数重载。
- 每个区域包含 `BlockStatePalette`（`List<Compound>`）、`BlockStates`（`LongArray`）、`Position` 和 `Size`。
- **位宽不写进文件**，必须由调色板长度推导：`bits = max(2, 32 - numberOfLeadingZeros(n-1))`。
  解包使用 Litematica 自有的公式：低位在前，条目可以跨 long。它不是原版 `PalettedContainer`
  的 4 位对齐方案。
- 索引公式为 `y*(sizeX*sizeZ) + z*sizeX + x`，x 变化最快。`Position` 是相对偏移；`Size` 各轴可能为负，
  按 min/max 归一化。
- 调色板条目就是原版方块状态 NBT，直接交给 `NbtHelper.toBlockState(Registries.BLOCK, entry)`。

### 4.2 方块到物品

默认取 `state.getBlock().asItem().getDefaultStack()`，与上游 `MaterialCache` 在 `world == null` 时的分支一致。
在此之上叠加特殊方块表：

| 方块 | 折算 |
| --- | --- |
| 含水方块 | 基础物品加 1 个水桶 |
| 双台阶 | 2 个台阶 |
| 雪层 | `LAYERS` 个 |
| 海龟蛋、海泡菜、蜡烛 | 按属性数量 |
| 多面方块 | 按可用面数 |
| 门、床、高植株 | 只计下半 |
| 活塞头、移动活塞、传送门、末地门 | 不计 |
| 耕地 | 泥土 |
| 水源、岩浆源（`LEVEL=0`） | 对应的桶；流动液体不计 |
| 花盆 | 花盆加盆栽物 |
| 炼药锅 | 炼药锅加对应的瓶或桶 |

### 4.3 三个已知问题

**子区域启用状态不同步**。Syncmatica 的 `SubRegionPlacementModification` 只带 name、position、rotation 和 mirror，
不含 enabled。本模组按约束 6 统计全量，界面用 `lmp.gui.status.full_schematic` 明确标注。

**老版本 `.litematic`**。判据是文件实际能不能解析，而不是版本号。真正的危险是静默算错：
旧方块名在当前注册表里不存在时，`NbtHelper.toBlockState` 会静默回落成 AIR，所需清单算少、进度虚高。
因此拒绝条件是「调色板里出现有名字却解析成 AIR 的条目」。这个条件与版本号无关，直接命中该失败模式。

`MinecraftDataVersion` 高于当前运行版本时直接拒绝，因为格式未知；低于当前版本时只告警，然后继续解析。
否则服务器每升级一次游戏版本，所有已共享的原理图都会永久变成「所需未知」。

**读不到或解析失败时，所需材料标记为未知**。`LitematicRequiredMaterialSource` 在任一环节失败
（Syncmatica 未装、placement 不存在、文件未同步、解析失败、算出的清单为空）都返回 `Optional.empty()`，
绝不用空清单冒充「什么都不需要」。失败结果缓存 60 秒，因为 Syncmatica 先广播 placement、
原理图随后落盘是正常时序；成功结果永久缓存，因为文件按内容哈希命名，内容一变就是另一个 placement。

### 4.4 潜影盒的口径不对称

这是上游既有行为，不是本模组的缺陷。所需侧每个潜影盒**方块**只计 1 个盒子，从不读 `TileEntities`；
已收集侧则统计非空潜影盒的内容物，并乘以 `stack.getCount()`。两侧都按此实现。

## 5. 协议

协议使用自有 id，命名空间为 `lmp`。

| 方向 | payload | 字段 |
| --- | --- | --- |
| C2S | `lmp:request` | `regionId`、`knownRevision`、`forceRescan` |
| S2C | `lmp:region_list` | 每条摘要含 `id`、`name`、`dimension`、`placementId`、`pos1`/`pos2`、`revision`、`lastScanAt`、`coveragePermille` |
| S2C | `lmp:status` | `regionId`、`phase`、`revision`、`chunksDone`、`chunksTotal`、`containersScanned`、`coveragePermille` |
| S2C | `lmp:snapshot` | `regionId`、`revision`、`status`（COMPLETE/PARTIAL/UNKNOWN）、`coveragePermille`、`entries[itemIdentifier, required, present]` |

实现要点：

- **先注册 payload 类型，再注册接收器**，否则 `assertPayloadType` 失败。接收器签名为
  `void receive(T payload, Context ctx)`；返回 boolean 的是 `registerGlobalReceiver`。
- 请求按 **regionId（UUID）** 索引，物品键用注册表 `Identifier`，不用 raw id。
- 区域摘要带 `pos1`/`pos2`，客户端才能回答「玩家站在哪个材料区里」并显示体积；否则热键只能盲选。
- `forceRescan` 区分两种刷新。「刷新扫描」按钮由玩家显式触发，走按玩家限流；TTL 自动重扫保持静默。
- 包大小：约 2000 条目为 12–16 KB，S2C 上限为 1 MiB。更大的区域交给 Fabric 的分包机制。
- **版本一致性**：服务端只在快照的 `revision` 与区域当前 `revision` 一致时下发。区域改名、换绑定或改范围后，
  旧快照描述的已不是当前区域。客户端也丢弃 `revision` 更小的乱序快照。
- **权限**：只读地取快照对所有玩家开放。`forceRescan` 会让服务端做一次全区域扫描，因此与 `/lmp scan`
  共用 GAMEMASTERS 权限闸，payload 不能成为命令权限的旁路。
- **接收器只注册一次**。Fabric 的全局接收器重复注册会静默失败，JOIN 监听器则会不断累积，
  而集成服务器可以在同一 JVM 里反复起停。接收器因此注册一次，且闭包不捕获某一轮的实例，
  每次回调取「当前」实例；否则重启之后仍由上一张地图的 store 和 scheduler 提供服务。
- **协议层降级**：所有发送前检查 `canSend`。服务端未安装本模组时，客户端显示明确状态，不抛异常。
  区域定义、删除和扫描走**命令**而非 payload。界面本身没有降级路径，见 6.1。

## 6. 客户端

### 6.1 进度界面

进度界面复用 Litematica 的 `GuiMaterialList`：

- `LmpMaterialList extends MaterialListBase` 把服务端快照映射成行：
  `MaterialListEntry(stack, countTotal=所需, countMissing=还差, countMismatched=0, countAvailable=已收集)`。
- `LmpGuiMaterialList extends GuiMaterialList` 是薄子类，补两件上游不管的事。

复用上游界面有三个实测陷阱。它们都来自阅读上游源码，不是推测：

1. `GuiMaterialList` 的构造函数会调用
   `MaterialListUtils.updateAvailableCounts(materialList.getMaterialsAll(), mc.player)`，
   把 **Available 列覆盖成玩家背包里的数量**；材料清单 HUD 每 2 秒还会再覆盖一次。
   本模组的 Available 是「材料区内已收集」，含义不同。因此重写 `getMaterialsAll()` 返回**独立副本**，
   让上游的改写落在副本上，真实列表保持服务端数据。
2. 构造函数会调用 `setCompletionListener(this)`，覆盖外部设置的监听器。刷新控件要依赖它的回调：
   `setMaterialListEntries` 之后触发 `onTaskCompleted()`，再调用 `initGui()`。
3. 上游的汇总行（Total / Done / Missing）只覆盖数量维度。约束 7 要求两个维度都显示，
   因此种类进度挂在 `getTitleString()` 上，每次重绘重算。

`reCreateMaterialList()` 重读客户端缓存并向服务端要一次快照，**绝不触发扫描**。是否真的重扫由服务端
按快照 TTL 决定，所以反复点「刷新」不会变成扫描风暴。强制重扫走 `/lmpc refresh`。

标题后缀标注两件容易被误当成缺陷的事：

- **全量原理图**：服务端不看玩家在 Litematica 里禁用了哪些子区域，两边数字可能对不上。
- **结果不完整，覆盖率 x‰**：有区块没有加载。

**没有降级界面。** 曾经写过一条自绘的降级路径，已经删除。它只能挡「Litematica 不在」，
挡不住「Litematica 在但内部包改了签名」，而后者才是更可能发生的坏法：那时 `isModLoaded` 仍返回 true，
照样走上游分支。为一个边缘场景维护第二套 400 行界面不划算。

**Litematica 也进不了 `depends`，这一点经过实测。** 本模组的扫描在服务端运行，专用服务器不会安装 Litematica，
而 Fabric 的 `depends` 无法按环境区分。把它写进 `depends` 后，专用服务器直接以
`HARD_DEP_NO_CANDIDATE lmp {depends litematica}` 拒绝加载本模组，功能整个消失。

最终形态：Litematica 和 malilib 留在 `suggests`，并带上版本范围（`>=0.26.16 <0.27.0-`、
`>=0.27.20 <0.28.0-`，仅供人阅读）。客户端打开界面时做两件事：没装就给出明确提示；
装了但 API 变了就捕获 `LinkageError` 并提示。两者都在 `LmpProgressScreens` 里。

### 6.2 入口

| 入口 | 实现 | 说明 |
| --- | --- | --- |
| 热键 `K` | `LmpKeybinds` | 默认键与 Litematica 的 M、I 以及原版都不冲突；分类独立为 `key.category.lmp.main` |
| `/lmpc gui [名称]` | `LmpClientCommands` | 无参数时按「脚下、当前维度唯一、列出可选」的顺序选择区域 |
| Syncmatica 按钮 mixin | 未实现 | 依赖上游按钮索引，会随版本失效；已记为后续项 |

### 6.3 区域录入

区域录入以命令为唯一真相。客户端命令 `/lmpc` 刻意带后缀：连接服务器时服务端命令会覆盖同名客户端命令，
而 `/lmp` 是服务端命令树。`/lmpc` 自己不修改状态，只把动作翻译成服务端命令再发出去，
因此服务端的校验、权限和限流一条都不少。

| 客户端命令 | 翻译成 | 用途 |
| --- | --- | --- |
| `/lmpc pos1`、`/lmpc pos2` | `lmp region pos1 <x y z>` | 用 `MinecraftClient.crosshairTarget` 取视线方块，不依赖 Litematica 内部 API |
| `/lmpc from-selection <名称>` | `pos1` + `pos2` + `create` | 读取 Litematica 当前选区；多个子区域取并集 |
| `/lmpc create <名称>` | `lmp region create <名称>` | 固化区域 |
| `/lmpc bind <材料区> <placementId>` | `lmp region bind` | 绑定共享放置 |
| `/lmpc placements` | 纯客户端 | 列出 Syncmatica 客户端已知的放置，点击即可填入绑定命令，不必手抄 UUID |
| `/lmpc refresh [名称]` | `lmp:request{forceRescan}` | 强制重扫，仍受服务端按玩家限流 |

Litematica **没有**选区变更的公开监听器：placement 有 `SchematicPlacementEventHandler`，选区没有。
因此 `from-selection` 只在按下时读一次快照，不做持续跟随。

**未实现**（已记为 LMP-7）：在 `create` 时按「包含区域中心的 placement」自动绑定。它需要 placement 的包围盒，
而 Syncmatica 的 `ServerPlacement` 只有位置、旋转和镜像，没有尺寸。尺寸要由第 4 节的解析器读出
`Metadata.EnclosingSize` 之后才能自动绑定。

## 7. 数据模型

每个世界写入一个 `lmp/regions.json`，位置在 `MinecraftServer.getSavePath(WorldSavePath.ROOT)` 下。
写入是原子的：先写临时文件，再替换目标文件。

```
{ version, regions: [ { id, name, dimension, pos1, pos2, placementId?, owner, createdAt,
                        lastScanAt, revision, coveragePermille } ] }
```

**坐标运算一律使用 long。** `pos2 - pos1 + 1` 在 int 域里会溢出：极端坐标下
`Integer.MAX_VALUE - Integer.MIN_VALUE + 1` 恰好回绕成 0，于是「整张地图那么大」的区域体积算成 0、
通过校验，最后在分配区块数组时让服务端线程崩溃。现在 `volume()` 逐轴封顶后再相乘，不会溢出 long；
`chunkCount()` 返回 long；`chunks()` 对未校验或超限的区域直接抛异常，而不是分配负长度数组。

**写盘失败要告诉玩家。** `save()` 返回是否成功。内存里的改动是成功的，但重启后会消失；
只留一条服务端日志，玩家会以为已经存住了。

内存中的快照为 `{ revision, status, coveragePermille, Map<Item,long> present, Map<Item,int> required, missing }`。
完成态快照可以与 `regions.json` 一起持久化，前提是快照与 `revision` **同时**持久化；进行中的扫描状态不持久化。
`placementId` 是可选的，将来支持「基地级共享区域」只需改数据，不需重构。

**不要**写入 Syncmatica 的 `ServerPlacement.matList`：它的 setter 判断写反了
（`if (this.matList != null)`），因此永不赋值，也从不序列化。这是上游缺陷。

## 8. 实现状态与验证

| 里程碑 | 状态 |
| --- | --- |
| M0 仓库、许可、构建 | 完成 |
| M1 服务端异步扫描器、命令、客户端取数闭环 | 完成 |
| M2 `.litematic` 解析与所需材料计算 | 完成 |
| M3 进度界面、入口、区域录入、重扫策略 | 完成 |

自动化验证分三层，都可以在仓库里复现：

| 命令 | 覆盖 |
| --- | --- |
| `./gradlew test` | 66 个单元测试：计数语义、区域校验与存储、原理图解析、材料折算、进度算术 |
| `./gradlew runGameTest` | 5 个 GameTest，跑在真实服务端上：扫描真实区块里的方块实体、取消后不复活已删除区域、正常完成的扫描照常落库、扫描开销测量 |
| `./gradlew runClientGameTest` | 1 个客户端 GameTest：真实客户端里打开进度界面、断言两个维度的数值、Available 列语义与监听者不泄漏，并留下截图 |

前两层并入 `./gradlew build`。第三层会拉起图形客户端，只适合人工触发。

测量类用例受同批其它测试干扰：同一批的结构都摆在同一个区域，并并发消耗 tick。单独跑一批可以得到干净数据：

```bash
./gradlew runGameTest -PlmpTestFilter=lmp-gametest:lmp_scan_cost_game_test_measures_scan_cost_with_arealistic_load
```

第 3.3 节的数字来自单独跑。

## 9. 已知限制

- **未与真实原理图对拍**。全部 `.litematic` 测试数据都是手写 NBT，已与上游源码语义逐条对照，
  但没有拿一份真实的 Litematica 导出文件做过逐项比对。
- **测量环境不是生产服务器**。第 3.3 节的数据来自本机开发机（真实专用服务端加游戏测试框架）。
  文件系统与杀毒软件行为差异较大，换机器后建议重跑 `runGameTest` 复核。
- **开发运行期缺少 conditional-mixin**。客户端日志会报
  `NoClassDefFoundError: ...RestrictiveMixinConfigPlugin`，Litematica 与 malilib 按条件启用的那部分 mixin
  因此没有加载。界面功能正常，但开发环境并不完全等同于生产环境。
- **上游耦合**。`GuiMaterialList` 与 `SelectionManager` 都是内部包，没有 API 稳定性承诺。
  取舍见 6.1：不做降级界面，只在打开界面时捕获 `LinkageError` 并提示。Syncmatica 的按钮 mixin 未实现。
- **许可**。Litematica、malilib、Servux 为 LGPL-3.0，Syncmatica 为 CC0-1.0。本项目不复制其代码，
  只做链接与继承。

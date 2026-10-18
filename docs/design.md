# 设计：材料区收集进度

本文记录已核实的架构、决策与里程碑。所有 Minecraft / Fabric API 名称均针对 **1.21.11** 实测核对
（yarn 映射为本项目使用的映射；括号内为上游 mojang 映射名）。

## 1. 目标与约束

**目标**：为共享原理图（Litematica + Syncmatica）划定一块**材料区**（容器仓库），在游戏内菜单显示
原理图所需材料的**收集进度**。

**硬约束**（来自用户，违反即方案错误）：

1. **扫描必须在服务端**——客户端不做逐箱 NBT 请求。
2. **服务端维护权威列表**，客户端只获取/接收。
3. **扫描必须异步分片**，不得抬高 MSPT；可取消、可恢复。
4. 「已收集」= **材料区内的容器**（不含玩家背包、不含已放置方块）。
5. 材料区**按 placement 一个**；只统计**箱子类**（箱子/陷阱箱/木桶）并递归箱内潜影盒/收纳袋；
   末影箱、漏斗、发射器、矿车等不计。
6. 所需材料按**全量原理图**统计（UI 必须标注：玩家在 Litematica 中禁用子区域时，服务端算出的
   所需会大于其界面所示，需明确说明以免被当成缺陷）。
7. 进度同时显示**完成种类/总种类**与**已收集数/所需数**。
8. 完全重实现，不复制上游代码（LGPL-3.0 只允许链接与继承）。

## 2. 架构

独立 Fabric 附加模组，`environment: "*"`。分层：

| 层 | 职责 | 关键实现 |
| --- | --- | --- |
| 服务端扫描器 | 分片扫描材料区容器并聚合 | `ServerTickEvents.END_SERVER_TICK` + 自适应预算 |
| 服务端状态 | 材料区定义 + 快照 + revision | 每世界 JSON，原子写 |
| 所需材料 | 从 Syncmatica 服务端保存的 `.litematic` 解析 | 服务端自行解析，不依赖客户端 |
| 协议 | C2S 请求 / S2C 快照与状态 | 自有命名空间 `lmp` |
| 客户端 | 菜单与区域录入 | 复用 Litematica `GuiMaterialList` |

**为什么不扩展上游协议**（已核实，非风格选择）：Fabric 每个 payload id 只允许一个全局接收器
（`registerGlobalReceiver` 内部 `putIfAbsent`，重复返回 `false`；`PayloadTypeRegistry.register`
重复直接抛异常），因此 `servux:litematics` 无法共享；而 `syncmatica` 命名空间会被 Syncmatica 的
4 个 `handleCustomPayload` mixin 无条件强转为 `SyncmaticaPacket.Payload`，外来 payload 会
`ClassCastException`。

## 3. 服务端扫描器

**驱动**：`ServerTickEvents.END_SERVER_TICK` → `ScanScheduler.tick(server)`。
单飞任务 + volatile `cancelled` + 可恢复游标（区块列表索引 + 单区块内 `List<BlockPos>` 快照）。

**每 tick 自适应预算**：`budget = min(2ms, max(0, 50ms - server.getAverageTickTime() - margin))`；
已超预算则整片跳过。**预算检查放在每个方块实体的循环顶部**——一个全是告示牌/床/刷怪笼的区块
不会触发「容器上限」，只在容器处检查会冲过头。

**区块访问**：只用 `ServerChunkManager.getWorldChunk(cx, cz)`（mojang `getChunkNow`）——
非服务端线程返回 `null`，未加载返回 `null`，**永不强制加载**；为 `null` 则跳过并标记 `PARTIAL`，
游标前进。区块内先快照 `WorldChunk.getBlockEntities().keySet()` 为 `List<BlockPos>`（位置不可变，
可跨 tick），按索引恢复，再用 `WorldChunk.getBlockEntity(pos)` 取实体并**重新判定**类型。

**禁止调用**（会强制加载或阻塞 tick 线程）：

- `World.getBlockEntity(pos)`——内部走 `getBlockEntity(pos, CreationType.IMMEDIATE)`，可在 tick 线程上强制加载。
- `ServerChunkManager.getChunk(x, z, ChunkStatus.FULL, true)`——阻塞等待磁盘 I/O。
- 跨 tick 持有 `WorldChunk` / `ItemStack` 引用。

**线程模型**（已核实）：`ItemStack` 非线程安全（可变 `count` + 可变 `MergedComponentMap`），
世界与容器读取只能在服务端主线程；可离线程的只有纯快照（排序、组包、`.litematic` NBT 解析）。

**容器判定**：`be instanceof net.minecraft.inventory.Inventory`（mojang `Container`）覆盖全部原版容器
方块实体；本项目按约束 5 再收窄为箱子类白名单。

**双箱**：只数每个 `ChestBlockEntity` 自己的 27 格 Inventory；**禁止**用
`ChestBlock.getInventory(...)`（返回覆盖两半的 `DoubleInventory`，会翻倍）。

**递归与计数语义**：`DataComponentTypes.CONTAINER` → `ContainerComponent.iterateNonEmpty()`；
`BUNDLE_CONTENTS` → `BundleContentsComponent.iterate()`。**组件缺失或为空时，容器物品本身计 1**
（对齐 Litematica）。参考实现 `chenjicheng/gg` 的 `ItemCountManager.countRecursive` 在组件存在时
直接 `return`，会把空潜影盒/空收纳袋算成 0，并在递归时不乘 `stack.getCount()`——本项目重写该逻辑
并用单元测试覆盖（空潜影盒、堆叠潜影盒、收纳袋、双箱不翻倍）。

**取消**：区域变更、请求者退出、`ServerLifecycleEvents.SERVER_STOPPING`、被新任务取代；
另有每玩家请求限流与合并 dirty 标记。

## 4. 所需材料清单（服务端）

Syncmatica 服务端保存 `.litematic`（`gameDir/syncmatics/<hash>.litematic`），
placementId→hash 可用其公开 API 解析：
`Syncmatica.getContext(Syncmatica.SERVER_CONTEXT).getSyncmaticManager().getPlacement(uuid).getHash()`。

解析 `Regions.<name>.{BlockStatePalette, BlockStates, Position, Size}`，
读取用 `NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes())`（单参重载不存在）。
方块→物品先用 `state.getBlock().asItem().getDefaultStack()`（yarn；mojang 为 `getDefaultInstance`），
M2 再补特殊方块表。

**必须处理的三个坑**：

1. **子区域启用状态不同步**：Syncmatica 的 `SubRegionPlacementModification` 只带
   name/position/rotation/mirror，不含 enabled。本项目按约束 6 统计**全量**，因此 UI 必须标注
   「所需按全量原理图统计」。
2. **老版本 `.litematic`** 需要调色板数据版本转换；M2 必须做出明确取舍（拒绝并提示，或移植转换）。
3. **读不到/解析失败 → required = UNKNOWN**，绝不可退化为空清单（会显示 0% 进度，比不显示更误导）。

## 5. 协议

自有 id，命名空间 `lmp`：

- C2S `lmp:request` = {placementId UUID, knownRevision varint, flags}
- S2C `lmp:status` = {regionId, phase, revision, chunksDone, chunksTotal, containersScanned, coveragePermille}
- S2C `lmp:snapshot` = {regionId, revision, status(COMPLETE/PARTIAL/UNKNOWN), coveragePermille, entries[itemIdentifier, required, present]}

要点：

- **先注册 payload 类型再注册接收器**（否则 `assertPayloadType` 失败）；
  接收器签名为 `void receive(T payload, Context ctx)`（返回 boolean 的是 `registerGlobalReceiver`）。
- 请求按 **placementId(UUID)** 索引，不用 regionId varint（客户端只从 Syncmatica 握手获得 placement id）。
- 物品键用注册表 `Identifier`，不用 raw id。
- 实测定稿的字段：`lmp:request` = {regionId, knownRevision, forceRescan}；
  `lmp:region_list` 的每条摘要带 **pos1/pos2**——客户端要回答「我站在哪个材料区里」并显示体积，
  否则热键只能盲选。`forceRescan` 是「刷新扫描」按钮与 TTL 自动重扫的分界：
  前者由玩家显式触发、走按玩家限流，后者静默。
- **revision 在任何区域变更与服务器启动时 bump/reset**，快照与 revision 一起持久化；
  否则重启后客户端缓存的 `(regionId, revision)` 与服务端相同，会渲染空白或旧区域数据。
- 包大小：约 2000 条目 ≈ 12–16 KB，S2C 上限 1 MiB；更大区域用 Fabric splitter。
- 降级：`canSend` 检查；服务端无本模组时客户端显示明确状态、不崩。区域定义/删除/扫描走**命令**而非 payload。

## 6. 客户端

### 6.1 进度界面：复用 Litematica `GuiMaterialList`（已实现）

`LmpMaterialList extends MaterialListBase` 把服务端快照映射成行：
`MaterialListEntry(stack, countTotal=所需, countMissing=还差, countMismatched=0, countAvailable=已收集)`。
`LmpGuiMaterialList extends GuiMaterialList` 是薄子类，补两件上游不管的事。

**复用它的三个实测陷阱**（都不是猜测，是读上游源码得到的）：

1. `GuiMaterialList` 构造函数会调用
   `MaterialListUtils.updateAvailableCounts(materialList.getMaterialsAll(), mc.player)`，
   把 **Available 列覆盖成玩家背包里的数量**；材料清单 HUD 每 2 秒也会再覆盖一次。
   本模组的 Available 是「材料区内已收集」，含义不同 → 重写 `getMaterialsAll()`
   返回**独立副本**，让上游的改写落在副本上，真实列表保持服务端数据。
2. 构造函数还会 `setCompletionListener(this)` 覆盖我们设置的监听器 →
   依赖它的回调（`setMaterialListEntries` 之后触发 `onTaskCompleted()` → `initGui()`）来刷新控件，
   自己不要再设监听器。
3. 上游汇总行 "Total n / Done x% / Missing y%" 只覆盖**数量维度**。
   约束 7 要求两个维度都显示 → 种类进度挂在 `getTitleString()` 上，随每次重绘重算。

`reCreateMaterialList()` = 重读客户端缓存 + 向服务端要一次快照，**绝不触发扫描**：
是否真的重扫由服务端按快照 TTL 决定，因此反复点「刷新」不会变成扫描风暴。
强制重扫走 `/lmpc refresh` 或回退界面上的按钮（带 `forceRescan`）。

**降级路径**：没装 Litematica（或 malilib）时用自绘的 `LmpProgressScreen`
（两个进度维度、逐物品所需/已收集/还差、额外囤积、扫描进度、强制重扫）。
可选依赖必须真的可选——`LmpMaterialList` / `LmpSelectionBridge` 只在对应分支体里被引用，
JVM 按需解析常量池，没装 Litematica 的客户端不会碰到这些类。

### 6.2 入口

| 入口 | 实现 | 说明 |
| --- | --- | --- |
| 热键 `K` | `LmpKeybinds` | 默认键与 Litematica（M/I）和原版都不冲突；分类独立成 `key.category.lmp.main` |
| `/lmpc gui [名称]` | `LmpClientCommands` | 无参时按「脚下 → 当前维度唯一 → 列出来让玩家点」选区域 |
| 可选 Syncmatica 按钮 mixin | **未实现** | 依赖上游按钮索引，随版本失效；已记为后续项 |

### 6.3 区域录入（命令为唯一真相）

客户端命令 **`/lmpc`**（刻意带后缀：连接服务器时服务端命令会覆盖同名客户端命令，而 `/lmp` 是服务端命令树）。
它自己不修改任何状态，只把动作翻译成服务端命令再发出去，因此服务端校验、权限、限流一条都不少。

| 客户端命令 | 翻译成 | 用途 |
| --- | --- | --- |
| `/lmpc pos1` / `pos2` | `lmp region pos1 <x y z>` | `MinecraftClient.crosshairTarget` 取视线方块，不依赖 Litematica 内部 API |
| `/lmpc from-selection <名称>` | `pos1` + `pos2` + `create` | 读 Litematica 当前选区（多子区域取并集） |
| `/lmpc create <名称>` | `lmp region create <名称>` | 固化 |
| `/lmpc bind <材料区> <placementId>` | `lmp region bind` | 绑定共享放置 |
| `/lmpc placements` | 纯客户端 | 列出 Syncmatica 客户端已知放置，点击把 bind 命令填进输入框（不用手抄 UUID） |
| `/lmpc refresh [名称]` | `lmp:request{forceRescan}` | 强制重扫（仍受服务端按玩家限流） |

注意 Litematica **没有**选区变更的公开监听器（placement 有 `SchematicPlacementEventHandler`，选区没有），
因此 `from-selection` 只能是「按下时读一次快照」，不做持续跟随。

**未实现（已记为后续项）**：`create` 时按「包含区域中心的 placement」自动绑定。
它需要 placement 的包围盒，而 Syncmatica 的 `ServerPlacement` 只有位置/旋转/镜像、没有尺寸——
尺寸要等 M2 的 `.litematic` 解析器读出 `Metadata.EnclosingSize` 才能自动绑定。

## 7. 数据模型

每世界 `chestcounter`→`lmp/regions.json`，位于 `MinecraftServer.getSavePath(WorldSavePath.ROOT)` 下，
原子写（临时文件 + 替换）：

```
{ version, regions: [ { id, name, dimension, pos1, pos2, placementId?, owner, createdAt,
                        lastScanAt, revision, coveragePermille } ] }
```

内存快照：`{ revision, status, coveragePermille, Map<Item,long> present, Map<Item,int> required, missing }`。
完成态快照可与 `regions.json` 一起持久化（**快照与 revision 必须同时持久化**）；进行中的扫描状态不持久化。
`placementId` 设为**可选**，将来支持「基地级共享区域」只是数据变化，不需重构。
**不要**写入 Syncmatica 的 `ServerPlacement.matList`——其 setter 判断反了（`if (this.matList != null)`），
永不赋值也从不序列化，属上游缺陷。

## 8. 里程碑

- **M0（本仓库当前状态）**：仓库/许可/构建/文档骨架，可编译。
- **M1（已实现，MSPT 未实测）**：服务端异步扫描器 + 命令 + 客户端取数闭环。
- **M2**：`.litematic` 解析与所需材料计算（含特殊方块表与老版本策略），进度 join。
- **M3（已实现）**：`GuiMaterialList` 正式界面 + 自绘降级界面、热键与 `/lmpc` 入口、区域录入三前端、重扫策略（TTL/手动/限流）、i18n。

## 9. 风险

- **所需材料准确性**：Litematica 的特殊方块处理（含水、台阶=2、雪层、门/床、耕地→泥土、
  岩浆/水→桶、花盆/炼药锅）与用户倍率仅存在于客户端；M1 的 `asItem()` 兜底会偏。
- **MSPT 数字未实测**：2ms 预算与每容器微秒级均为估算，必须在真实服务端测量后再定。
- **上游耦合**：`GuiMaterialList`、`SelectionManager` 均为内部包，无 API 稳定性承诺；已用「固定版本 + 可选依赖守卫 + 自绘降级」兜住，Syncmatica 按钮 mixin 未实现。
- **许可**：Litematica / malilib / Servux 为 LGPL-3.0，本项目不复制其代码，只链接/继承。
- **测量与测试缺口**：本机无真实服务器与 Spark；扫描预算与「不加载区块」不变量需要真实环境验证。

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
- **revision 在任何区域变更与服务器启动时 bump/reset**，快照与 revision 一起持久化；
  否则重启后客户端缓存的 `(regionId, revision)` 与服务端相同，会渲染空白或旧区域数据。
- 包大小：约 2000 条目 ≈ 12–16 KB，S2C 上限 1 MiB；更大区域用 Fabric splitter。
- 降级：`canSend` 检查；服务端无本模组时客户端显示明确状态、不崩。区域定义/删除/扫描走**命令**而非 payload。

## 6. 客户端

**列表界面直接复用 Litematica 的 `GuiMaterialList`**（已核实可行）：

- `MaterialListBase` 是 `public abstract class implements IMaterialList`，抽象方法仅
  `getName()` / `getTitle()` / `reCreateMaterialList()`；`GuiMaterialList` 是 `public class`，
  构造函数为 `GuiMaterialList(MaterialListBase)`。
- 上游自带范例 `MaterialListCustom`（"From Stormatica by CubicMetre"）即「直接 item→数量 成表」，
  与本项目服务端快照同形；照其结构写 `MaterialListBase` 子类即可。
- 它渲染的列正是 **Total / Missing / Available**（颜色规则 `available >= missing ? 绿 : 红`），
  天然满足约束 7：映射为 `MaterialListEntry(stack, countTotal=所需, countMissing=还差, countMismatched=0, countAvailable=已收集)`。
- 代价与注意：内部包无 API 稳定性承诺 → 固定版本 + 可选依赖守卫，缺失时降级自绘；
  构造时会写入 `DataManager.setMaterialList(...)`（仅当为空），会接管 Litematica 的材料列表 HUD 与热键；
  `reCreateMaterialList()` 实现为「重读服务端快照」，绝不触发扫描；`countMismatched` 恒为 0。

**入口**（三选）：① mixin 重新启用 Syncmatica placement 行的「材料清单」按钮
（该按钮为 index 1，构造函数中 `setEnabled(false)`；索引依赖上游版本，用条件 mixin + 可选）；
② 快捷键；③ `/lmp gui`。

**区域录入（命令为唯一真相）**：

| 前端 | 实现 | 说明 |
| --- | --- | --- |
| `/lmp region pos1` / `pos2` | `MinecraftClient.crosshairTarget` → `BlockHitResult.getBlockPos()` | 主推，不依赖 Litematica 内部 API |
| `/lmp region from-selection` | `DataManager.getSelectionManager().getCurrentSelection()` → `AreaSelection.getAllSubRegions()` | 复用 Litematica 已有习惯；多 box 时取并集并提示，或要求单个 |
| `/lmp region set <x1 y1 z1> <x2 y2 z2>` | 精确坐标（支持 `~`） | 脚本/精确对齐 |

固化：`/lmp region create <名字>`（绑定当前 placementId 落服务端），配套 `info` / `list` / `delete`。
注意 Litematica **没有选区变更的公开监听器**（placement 有 `SchematicPlacementEventHandler`，选区没有），
因此 `from-selection` 只能是「按下时读一次快照」，不做持续跟随。

**服务端校验**：同维度、坐标归一化 min/max、**体积上限**（防 MSPT 的第一道闸，超限直接拒绝并说明）、
权限（fabric-permissions-api；Syncmatica 自身命令默认 `PermissionLevel.ALL`，不适合当区域编辑的闸）、
记录 owner / createdAt / revision。

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
- **M1**：服务端异步扫描器原型 + 命令 + 最小客户端取数闭环 + **MSPT 实测**（LMP-2）。
- **M2**：`.litematic` 解析与所需材料计算（含特殊方块表与老版本策略），进度 join。
- **M3**：`GuiMaterialList` 正式界面、入口（mixin/热键/命令）、区域录入 UX、重扫策略与 i18n。

## 9. 风险

- **所需材料准确性**：Litematica 的特殊方块处理（含水、台阶=2、雪层、门/床、耕地→泥土、
  岩浆/水→桶、花盆/炼药锅）与用户倍率仅存在于客户端；M1 的 `asItem()` 兜底会偏。
- **MSPT 数字未实测**：2ms 预算与每容器微秒级均为估算，必须在真实服务端测量后再定。
- **上游耦合**：`GuiMaterialList`、`SelectionManager` 均为内部包；mixin 按索引取按钮易随上游变更失效。
- **许可**：Litematica / malilib / Servux 为 LGPL-3.0，本项目不复制其代码，只链接/继承。
- **测量与测试缺口**：本机无真实服务器与 Spark；扫描预算与「不加载区块」不变量需要真实环境验证。

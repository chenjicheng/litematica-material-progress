# Litematica Material Progress

[![CI](https://github.com/chenjicheng/litematica-material-progress/actions/workflows/ci.yml/badge.svg)](https://github.com/chenjicheng/litematica-material-progress/actions/workflows/ci.yml)

这个 Fabric 模组为共享原理图（Litematica + Syncmatica）显示材料收集进度。

你在世界里划出一块**材料区**，把所有相关容器放进去。服务端异步扫描区内的容器，
游戏内菜单显示每种材料的所需数量、已收集数量和还差数量，并给出两个进度指标：
完成种类/总种类，已收集数/所需数。

## 状态

M0 到 M3 已实现：服务端异步分片扫描、`.litematic` 解析与所需材料计算、进度界面与区域录入。
区域录入已改为界面优先：入口挂在放置列表的共享原理图行上，不再需要玩家敲命令。

扫描开销已在真实服务端上实测。256 个区块、16384 个容器的区域，每 tick 花在扫描上的时间为
p50 2.00ms、p99 2.03ms，前后已加载区块数完全不变。

**尚未验证**：玩家真实存档里那些更复杂的原理图（模组方块、超大调色板、旧数据版本、多子区域）。
其余部分已经用 Litematica 自己的写入器生成的原理图对拍过，详见 [docs/design.md](docs/design.md) 的已知限制。

## 使用方法

### 1. 打开某个共享原理图的设置入口

在 Litematica 的放置列表（`M` → 原理图放置）里，每个**已共享**的原理图行上有一个「材料」按钮，
和 Syncmatica 的「分享」按钮排在同一行。点它打开该原理图的材料区设置界面。

> 没装 Syncmatica 时不会有这个按钮：共享放置是材料区的绑定对象，服务端要靠它找到 `.litematic`。

### 2. 划范围

设置界面里两种取范围方式，都不需要手打坐标：

- **用原理图范围**：直接取该原理图的包围盒。多数基地的材料区就是它。
- **用 Litematica 选区**：先用 Litematica 的选区工具框好范围，再点这个按钮（多个子区域取并集）。

名字默认取原理图名，可以改。界面上同时显示体积、区块数与上限，以及当前扫描状态。

### 3. 保存

点「保存」。区域立刻落到服务端并自动绑定这个共享原理图，处理结果以聊天栏消息回复。
一个共享原理图只能对应一个材料区；已被别的材料区占用时会明确拒绝。

### 4. 查看进度

- 设置界面里的「打开进度」。
- 热键 **K**：打开玩家**脚下**那个材料区的进度。不在任何区域内时，若**当前维度**只有一个材料区就打开它；
  否则打开材料区清单让你选。
- 清单里每一行右侧的「打开进度」。

进度界面复用 Litematica 的材料清单，三列分别对应：Total 为所需，Missing 为还差，Available 为已收集。
标题栏同时给出种类进度和数量进度，并标注「全量原理图」：服务端统计全量，不看你在 Litematica 里禁用了哪些子区域，
所以两边数字可能对不上。

> `Available` 列在本模组里表示**材料区内已收集**，不是玩家背包里的数量。

### 控制台命令

`/lmp region create <名称> <角点1> <角点2>`、`/lmp region bind|list|info|delete`、
`/lmp scan [名称]`、`/lmp status`。这些命令默认要求权限等级 2，保留给控制台、数据包与排障——
专用服务器上没有界面，运维仍然需要能看一眼、改一下区域。它们与界面走同一套校验。

界面上的动作走本模组自己的 payload，不经过文本命令，因此材料区名字可以含空格。

## 设计要点

完整说明见 [docs/design.md](docs/design.md)。

- **服务端权威**：扫描只在服务端进行，客户端只取结果列表，避免逐箱 NBT 往返。
  界面的保存请求也由服务端独立校验权限与区域约束，payload 不是命令权限的旁路。
- **异步分片**：扫描切成每 tick 的小片，带自适应预算；未加载的区块直接跳过，绝不为扫描加载区块。
- **取消即作废**：被取消的扫描不发布结果，否则会把刚删除的区域写回存储。
- **完全重实现**：不复制 Litematica、malilib、Syncmatica 的代码。正常路径只走公开 API；
  唯一例外是放置行上的入口按钮，它用 mixin 读上游的按钮游标（注入，不是复制）。
- **独立附加**：不修改 Syncmatica 与 Servux 的协议，使用自己的 payload 命名空间。

## 依赖

本模组**两端都要安装**：客户端负责界面与入口，服务端负责扫描、权威数据与原理图解析。
`fabric.mod.json` 里 `environment` 为 `*`，主入口与客户端入口并存；只有放置行按钮的 mixin 是客户端专属。
专用服务器只需要 Fabric API——Litematica 与 malilib 只在客户端需要，不会、也不应该装到专用服务器上。

| 组件 | 作用 | 必需 |
| --- | --- | --- |
| Fabric API | 事件、网络、命令 | 是，两端 |
| Litematica（sakura-ryoko 的 `LTS/1.21.11`） | 所需材料清单、进度界面、放置列表入口 | 客户端需要，按 `0.26.x` 构建 |
| malilib | GUI 组件 | 客户端需要，随 Litematica 一起安装，按 `0.27.x` 构建 |
| Syncmatica（同上分支） | 共享放置与 placementId | 需要「所需材料」时装，两端 |

## 构建与验证

需要 JDK 21。

```bash
./gradlew build              # 产物：build/libs/litematica-material-progress-<version>.jar，已 remap，可直接放入 mods/
./gradlew test               # 109 个单元测试
./gradlew runGameTest        # 5 个 GameTest，跑在真实服务端上
./gradlew runClientGameTest  # 3 个客户端 GameTest，跑在真实客户端里并留下截图
```

前两条并入 `./gradlew build`。客户端用例会拉起图形客户端，因此需要人工触发，截图落在
`build/run/clientGameTest/screenshots/`。

扫描开销测量会被同批其它测试干扰。单独跑一批可以得到干净数据：

```bash
./gradlew runGameTest \
  -PlmpTestFilter=lmp-gametest:lmp_scan_cost_game_test_measures_scan_cost_with_arealistic_load
```

> 不要使用 `build/devlibs/` 里的 `-dev.jar`。它使用 yarn 名称，装进游戏后会在首个界面初始化时
> 抛出 `NoClassDefFoundError`。

## 许可

LGPL-3.0。本模组为独立实现。Litematica、malilib、Servux 为 LGPL-3.0，Syncmatica 为 CC0-1.0，
本项目不复制它们的代码。

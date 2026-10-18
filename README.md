# Litematica Material Progress

这个 Fabric 模组为共享原理图（Litematica + Syncmatica）显示材料收集进度。

你在世界里划出一块**材料区**，把所有相关容器放进去。服务端异步扫描区内的容器，
游戏内菜单显示每种材料的所需数量、已收集数量和还差数量，并给出两个进度指标：
完成种类/总种类，已收集数/所需数。

## 状态

M0 到 M3 已实现：服务端异步分片扫描、`.litematic` 解析与所需材料计算、进度界面与区域录入。

扫描开销已在真实服务端上实测。256 个区块、16384 个容器的区域，每 tick 花在扫描上的时间为
p50 2.00ms、p99 2.03ms，前后已加载区块数完全不变。

**尚未验证**：与真实的 Litematica 导出文件做逐项比对。详见 [docs/design.md](docs/design.md) 的已知限制。

## 使用方法

### 1. 划定材料区

区域录入以服务端命令为唯一真相。`/lmpc` 只把动作翻译成服务端命令，服务端的校验、权限和限流一条都不少。

```
/lmpc pos1                 # 看向一个方块，记下角点 1
/lmpc pos2                 # 看向另一个方块，记下角点 2
/lmpc create 主基地仓库     # 固化为名为「主基地仓库」的材料区
```

如果已经用 Litematica 框好了选区，直接用它：

```
/lmpc from-selection 主基地仓库
```

### 2. 绑定共享原理图

绑定决定「所需材料」从哪里来。

```
/lmpc placements                       # 列出 Syncmatica 已知的共享放置
/lmpc bind 主基地仓库 <placementId>     # 点击列表中的放置即可填入命令
```

### 3. 查看进度

- 热键 **K**：打开玩家**脚下**那个材料区的进度。不在任何区域内时，若当前维度只有一个材料区就打开它；
  否则在聊天栏列出可选的区域名。
- `/lmpc gui [名称]`：打开指定材料区。
- `/lmpc refresh [名称]`：强制重扫。服务端会按玩家限流。

界面复用 Litematica 的材料清单，三列分别对应：Total 为所需，Missing 为还差，Available 为已收集。
标题栏同时给出种类进度和数量进度，并标注「全量原理图」：服务端统计全量，不看你在 Litematica 里禁用了哪些子区域，
所以两边数字可能对不上。

> `Available` 列在本模组里表示**材料区内已收集**，不是玩家背包里的数量。

### 服务端命令

`/lmp region pos1|pos2|create|bind|list|info|delete`、`/lmp scan [名称]`、`/lmp status`。
这些命令默认要求权限等级 2。

服务端只需要 Fabric API，专用服务器不必安装 Litematica。Syncmatica 缺失时模组不会崩溃，只是所需材料显示为未知。
客户端没装 Litematica 时，点击进度入口会得到一句明确提示。

## 设计要点

完整说明见 [docs/design.md](docs/design.md)。

- **服务端权威**：扫描只在服务端进行，客户端只取结果列表，避免逐箱 NBT 往返。
- **异步分片**：扫描切成每 tick 的小片，带自适应预算；未加载的区块直接跳过，绝不为扫描加载区块。
- **取消即作废**：被取消的扫描不发布结果，否则会把刚删除的区域写回存储。
- **完全重实现**：不复制 Litematica、malilib、Servux 的代码，只通过公开 API 与可选依赖交互。
- **独立附加**：不修改 Syncmatica 与 Servux 的协议，使用自己的 payload 命名空间。

## 依赖

| 组件 | 作用 | 必需 |
| --- | --- | --- |
| Fabric API | 事件、网络、命令 | 是，两端 |
| Litematica（sakura-ryoko 的 `LTS/1.21.11`） | 所需材料清单、进度界面 | 客户端需要，按 `0.26.x` 构建 |
| malilib | GUI 组件 | 客户端需要，随 Litematica 一起安装，按 `0.27.x` 构建 |
| Syncmatica（同上分支） | 共享放置与 placementId | 需要「所需材料」时装，两端 |

## 构建与验证

需要 JDK 21。

```bash
./gradlew build              # 产物：build/libs/litematica-material-progress-<version>.jar，已 remap，可直接放入 mods/
./gradlew test               # 66 个单元测试
./gradlew runGameTest        # 5 个 GameTest，跑在真实服务端上
./gradlew runClientGameTest  # 1 个客户端 GameTest，跑在真实客户端里并留下截图
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

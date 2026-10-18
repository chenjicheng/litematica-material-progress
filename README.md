# Litematica Material Progress

为**共享原理图**（Litematica + Syncmatica）添加「材料区 + 收集进度」的 Fabric 附加模组。

为某个共享原理图划定一块区域作为**材料区**（仓库/箱子区），由**服务端**异步扫描区域内的容器，
内置菜单显示：**所需 / 已收集 / 还差**，以及「完成种类/总种类」与「已收集数/所需数」两个进度指标。

## 状态

M0–M3 已实现：服务端异步分片扫描、`.litematic` 解析与所需材料、进度界面与区域录入入口。

**尚未验证**：真实客户端的界面运行记录（需要图形客户端），
以及用真实的 Litematica 导出文件做逐项对拍。详见 [docs/design.md](docs/design.md) 的风险一节。

扫描开销已在真实服务端上实测：256 区块 / 16384 个容器的区域，每 tick 花在扫描上的时间是
p50 2.00ms、p99 2.03ms，且前后已加载区块数完全不变（确认不加载区块）。

## 用法

### 1. 划定材料区

区域录入以服务端命令为唯一真相，`/lmpc` 只是把动作翻译成服务端命令（校验、权限、限流一条不少）。

```
/lmpc pos1                 # 看向一个方块，记下角点 1（也可以重复执行记角点 2 用 /lmpc pos2）
/lmpc pos2                 # 记下角点 2
/lmpc create 主基地仓库     # 固化成一个名为「主基地仓库」的材料区
```

装了 Litematica 时可以直接用已有选区：

```
/lmpc from-selection 主基地仓库
```

### 2. 绑定共享原理图（决定「所需材料」）

```
/lmpc placements                       # 列出 Syncmatica 已知的共享放置，点击即可填入绑定命令
/lmpc bind 主基地仓库 <placementId>
```

### 3. 看进度

- 热键 **K**（默认）：打开玩家**脚下**那个材料区的进度；不在任何区域内时，若当前维度只有一个就打开它，
  否则在聊天栏列出可点的区域名。
- `/lmpc gui [名称]`：打开指定材料区。
- `/lmpc refresh [名称]`：强制重扫（受服务端按玩家限流）。

界面复用 Litematica 的材料清单：**Total = 所需 / Missing = 还差 / Available = 已收集**，
标题栏同时给出「种类进度」与「数量进度」。没装 Litematica 时自动降级为自绘界面，功能等价。

> 注意：`Available` 一列在本模组里是**材料区内已收集**，不是玩家背包里的数量。

### 服务端命令

`/lmp region pos1|pos2|create|bind|list|info|delete`、`/lmp scan [名称]`、`/lmp status`
（默认要求权限等级 2）。服务端只需要 Fabric API；Litematica / Syncmatica 缺失时功能降级，不会崩。

## 设计要点（详见 docs/design.md）

- **服务端权威**：扫描只在服务端进行，客户端只取结果列表；避免逐箱 NBT 往返。
- **异步分片**：扫描切成每 tick 的小片并带自适应预算，未加载区块直接跳过，绝不为扫描加载区块。
- **取消即作废**：被取消的扫描不发布结果——否则会把刚被删除的区域写回存储。
- **完全重实现**：不复制 Litematica / malilib / Servux 的代码，仅通过公开 API 与可选依赖交互。
- **独立附加**：不修改 Syncmatica / Servux 的协议，使用自己的 payload 命名空间。

## 依赖

| 组件 | 作用 | 必需 |
| --- | --- | --- |
| Fabric API | 事件、网络、命令 | 是（两端） |
| Litematica（sakura-ryoko 的 `LTS/1.21.11`） | 所需材料清单、进度界面复用 | 客户端（缺失则用自绘界面） |
| Syncmatica（同上分支） | 共享放置与 placementId | 需要「所需材料」时（两端） |
| malilib | GUI 组件 | 客户端（随 Litematica 一起装） |

## 构建与验证

需要 JDK 21。

```bash
./gradlew build          # 产物：build/libs/litematica-material-progress-<version>.jar（已 remap，可直接放入 mods/）
./gradlew test           # 65 个单元测试
./gradlew runGameTest        # 5 个 GameTest，跑在真实服务端上（gametest 源集，不进入发布产物）
./gradlew runClientGameTest  # 1 个客户端 GameTest：真实客户端里跑两条界面路径并留下截图
```

客户端用例会拉起一个图形客户端，因此不并入 `build`，需要人工触发；截图落在
`build/run/clientGameTest/screenshots/`。

扫描开销测量会受同批其它测试干扰，单独跑一批拿干净数据（把测试名换成 `-PlmpTestFilter` 的值）：

```bash
./gradlew runGameTest   -PlmpTestFilter=lmp-gametest:lmp_scan_cost_game_test_measures_scan_cost_with_arealistic_load
```

> 不要使用 `build/devlibs/` 里的 `-dev.jar`：它使用 yarn 名称，装进游戏会在首个界面初始化时 `NoClassDefFoundError`。

## 许可

LGPL-3.0。本模组为独立实现；Litematica / malilib / Servux 为 LGPL-3.0，Syncmatica 为 CC0-1.0，
本项目不复制它们的代码。

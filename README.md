# Litematica Material Progress

为**共享原理图**（Litematica + Syncmatica）添加「材料区 + 收集进度」的 Fabric 附加模组。

为某个共享原理图划定一块区域作为**材料区**（仓库/箱子区），由**服务端**异步扫描区域内的容器，
内置菜单显示：**所需 / 已收集 / 还差**，以及「完成种类/总种类」与「已收集数/所需数」两个进度指标。

## 状态

**骨架阶段（M0）**：仓库、构建、许可、文档与可编译入口已就位，功能尚未实现。
路线图见 [docs/design.md](docs/design.md)。

## 设计要点（详见 docs/design.md）

- **服务端权威**：扫描只在服务端进行，客户端只取结果列表；避免逐箱 NBT 往返。
- **异步分片**：扫描切成每 tick 的小片并带自适应预算，未加载区块直接跳过，不为扫描加载区块。
- **完全重实现**：不复制 Litematica / malilib / Servux 的代码，仅通过公开 API 与可选依赖交互。
- **独立附加**：不修改 Syncmatica / Servux 的协议，使用自己的 payload 命名空间。

## 依赖

| 组件 | 作用 | 必需 |
| --- | --- | --- |
| Fabric API | 事件、网络、命令 | 是（两端） |
| Litematica（sakura-ryoko 的 `LTS/1.21.11`） | 原理图与所需材料清单、进度界面复用 | 客户端 |
| Syncmatica（同上分支） | 共享放置与 placementId | 服务端 + 客户端 |
| malilib | GUI 组件（M3） | 客户端 |

## 构建

需要 JDK 21。

```bash
./gradlew build          # 产物：build/libs/litematica-material-progress-<version>.jar（已 remap，可直接放入 mods/）
./gradlew test           # 单元测试
```

> 不要使用 `build/devlibs/` 里的 `-dev.jar`：它使用 yarn 名称，装进游戏会在首个界面初始化时 `NoClassDefFoundError`。

## 许可

LGPL-3.0。本模组为独立实现；Litematica / malilib / Servux 为 LGPL-3.0，Syncmatica 为 CC0-1.0，
本项目不复制它们的代码。

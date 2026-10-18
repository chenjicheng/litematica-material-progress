package com.chenjicheng.lmp.material;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import com.chenjicheng.lmp.LmpMod;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 在服务端把 Syncmatica 的 placementId 解析成对应的 {@code .litematic} 文件路径。
 *
 * <p>路径链路（已按 Syncmatica 上游实现核对）：Syncmatica 服务端把每个共享原理图存成
 * {@code <游戏目录>/syncmatics/<内容哈希>.litematic}——文件名是 placement 的 <b>哈希(UUID)</b>，
 * 不是显示名、也不是 placement id。因此：
 * <pre>
 *   placementId --(SyncmaticManager.getPlacement)--> ServerPlacement.getHash()
 *               --> &lt;gameDir&gt;/syncmatics/&lt;hash&gt;.litematic
 * </pre>
 * placementId→hash 的映射本身来自 {@code <存档目录>/syncmatica/placements.json}，这里通过 Syncmatica
 * 的公开 API 读取，不自己解析该文件。
 *
 * <p><b>可选依赖守卫</b>：Syncmatica 是编译期可见、运行期可选的依赖。本类先检查模组是否加载，
 * 再把所有对 Syncmatica 类的直接引用隔离在 {@link SyncmaticaBridge} 里——否则未安装时类加载会崩。
 * 任何一步失败（模组缺失、上下文未初始化、placement 不存在、文件不在）都返回
 * {@link Optional#empty()}，不抛异常。
 */
public final class SchematicLocator
{
    private static final String SYNCMATICA_MOD_ID = "syncmatica";

    private SchematicLocator()
    {
    }

    /**
     * 解析 placementId 对应的服务端原理图文件。
     *
     * @param placementId Syncmatica 的共享放置标识（可为 null）
     * @return 存在的 {@code .litematic} 路径；找不到或不可用时为 {@link Optional#empty()}
     */
    public static Optional<Path> locate(UUID placementId)
    {
        if (placementId == null)
        {
            return Optional.empty();
        }

        if (!FabricLoader.getInstance().isModLoaded(SYNCMATICA_MOD_ID))
        {
            return Optional.empty();
        }

        try
        {
            return SyncmaticaBridge.locate(placementId);
        }
        catch (RuntimeException | LinkageError e)
        {
            LmpMod.LOGGER.warn("查询 Syncmatica 原理图失败（placement={}）：{}", placementId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 隔离层：唯一直接引用 Syncmatica 类的地方，只在确认模组已加载后才被调用（懒加载）。
     */
    private static final class SyncmaticaBridge
    {
        private static Optional<Path> locate(UUID placementId)
        {
            final ch.endte.syncmatica.Context context =
                    ch.endte.syncmatica.Syncmatica.getContext(ch.endte.syncmatica.Syncmatica.SERVER_CONTEXT);

            if (context == null || !context.isServer())
            {
                return Optional.empty();
            }

            final ch.endte.syncmatica.data.SyncmaticManager manager = context.getSyncmaticManager();

            if (manager == null)
            {
                return Optional.empty();
            }

            final ch.endte.syncmatica.data.ServerPlacement placement = manager.getPlacement(placementId);

            if (placement == null || placement.getHash() == null)
            {
                return Optional.empty();
            }

            final Path folder = context.getLitematicFolder();

            if (folder == null)
            {
                return Optional.empty();
            }

            final Path file = folder.resolve(placement.getHash().toString() + ".litematic");

            return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
        }
    }
}

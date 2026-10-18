package com.chenjicheng.lmp.client;

import java.util.ArrayList;
import java.util.List;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.Syncmatica;
import ch.endte.syncmatica.data.ServerPlacement;

/**
 * 读取 Syncmatica 客户端已知的共享放置。
 *
 * <p>用途只有一个：让玩家能选中「这块材料区属于哪个共享原理图」而不用手抄 UUID。
 * 服务端才是解析 {@code .litematic} 与计算所需材料的地方，这里读到的 placement id
 * 只是交给服务端命令 {@code /lmp region bind} 的参数。
 *
 * <p>Syncmatica 不存在或客户端上下文尚未启动时返回空列表，调用方据此提示。
 */
public final class LmpSyncmaticaBridge
{
    /** 供界面/命令使用的放置摘要。 */
    public record Placement(String id, String name, String dimension)
    {
    }

    private LmpSyncmaticaBridge()
    {
    }

    public static List<Placement> placements()
    {
        Context context = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);

        if (context == null || !context.isStarted())
        {
            return List.of();
        }

        List<Placement> result = new ArrayList<>();

        for (ServerPlacement placement : context.getSyncmaticManager().getAll())
        {
            result.add(new Placement(placement.getId().toString(), placement.getName(), placement.getDimension()));
        }

        return result;
    }
}

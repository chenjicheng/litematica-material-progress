package com.chenjicheng.lmp.client;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.client.gui.LmpProgressScreens;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 界面入口的公共逻辑：决定「按键/无参命令该打开哪个材料区」。
 *
 * <p>优先级：玩家脚下的材料区 → 当前维度唯一的材料区 → 多个时列出让玩家自己选。
 * 之所以要「脚下优先」，是因为在基地里按热键时，玩家想要的多半就是自己正站着的那块区域。
 */
public final class LmpClientEntry
{
    private LmpClientEntry()
    {
    }

    public static void openDefault(MinecraftClient client)
    {
        ClientProgressStore store = ClientProgressStore.get();

        if (!store.serverSupportsUs())
        {
            message(client, Text.translatable("lmp.gui.status.server_missing").formatted(Formatting.RED));
            return;
        }

        List<LmpPayloads.RegionInfo> regions = store.regions();

        if (regions.isEmpty())
        {
            message(client, Text.translatable("lmp.gui.status.region_empty").formatted(Formatting.YELLOW));
            return;
        }

        LmpPayloads.RegionInfo target = regionAtPlayer(client, regions);
        List<LmpPayloads.RegionInfo> here = inPlayerDimension(client, regions);

        // 「唯一」指的是**当前维度**唯一：别的维度有十个材料区，也不该让玩家在这里被反问
        if (target == null && here.size() == 1)
        {
            target = here.get(0);
        }

        if (target == null)
        {
            // 以前这里往聊天栏刷一行行「点一下填命令」的文字；命令行入口删掉之后，
            // 改成正经的清单界面
            LmpProgressScreens.openRegionList();
            return;
        }

        open(target);
    }

    public static void open(LmpPayloads.RegionInfo info)
    {
        LmpProgressScreens.open(info.id(), info.name());
    }

    /** 当前维度里的材料区。维度未知（还没进世界）时按「全都算」处理，避免把入口锁死。 */
    private static List<LmpPayloads.RegionInfo> inPlayerDimension(MinecraftClient client,
                                                                  List<LmpPayloads.RegionInfo> regions)
    {
        if (client.world == null)
        {
            return regions;
        }

        String dimension = client.world.getRegistryKey().getValue().toString();

        return regions.stream().filter(info -> dimension.equals(info.dimension())).toList();
    }

    /** 玩家脚下（含所站方块的坐标）的材料区；不在任何区域里则返回 null。 */
    private static LmpPayloads.RegionInfo regionAtPlayer(MinecraftClient client, List<LmpPayloads.RegionInfo> regions)
    {
        if (client.player == null || client.world == null)
        {
            return null;
        }

        Identifier dimension = client.world.getRegistryKey().getValue();
        BlockPos pos = client.player.getBlockPos();

        for (LmpPayloads.RegionInfo info : regions)
        {
            if (dimension.toString().equals(info.dimension()) && info.contains(pos))
            {
                return info;
            }
        }

        return null;
    }

    private static void message(MinecraftClient client, Text text)
    {
        if (client.player != null)
        {
            client.player.sendMessage(text, false);
        }
    }
}

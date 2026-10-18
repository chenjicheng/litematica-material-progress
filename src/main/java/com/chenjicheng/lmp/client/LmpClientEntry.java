package com.chenjicheng.lmp.client;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
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

        if (target == null && regions.size() == 1)
        {
            target = regions.get(0);
        }

        if (target == null)
        {
            listRegions(client, regions);
            return;
        }

        open(target);
    }

    public static void open(LmpPayloads.RegionInfo info)
    {
        LmpProgressScreens.open(info.id(), info.name());
    }

    public static Optional<LmpPayloads.RegionInfo> byName(String name)
    {
        String needle = name.toLowerCase(Locale.ROOT);

        return ClientProgressStore.get().regions().stream()
                .filter(info -> info.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
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

    private static void listRegions(MinecraftClient client, List<LmpPayloads.RegionInfo> regions)
    {
        message(client, Text.translatable("lmp.gui.list.header").formatted(Formatting.YELLOW));

        for (LmpPayloads.RegionInfo info : regions)
        {
            MutableText line = Text.literal("- " + info.name() + "  ")
                    .append(Text.literal(info.dimension() + "  ").formatted(Formatting.GRAY))
                    .append(Text.literal(info.volume() + " ").formatted(Formatting.GRAY))
                    .append(Text.translatable("lmp.gui.list.open").formatted(Formatting.AQUA))
                    .styled(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/lmpc gui " + info.name())));
            message(client, line);
        }
    }

    private static void message(MinecraftClient client, Text text)
    {
        if (client.player != null)
        {
            client.player.sendMessage(text, false);
        }
    }
}

package com.chenjicheng.lmp.client.gui;

import java.util.UUID;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import fi.dy.masa.malilib.gui.GuiBase;

import com.chenjicheng.lmp.LmpMod;

/**
 * 打开进度界面的唯一入口。
 *
 * <p><b>为什么 Litematica 不能写进 {@code depends}</b>：本模组的扫描在服务端跑，
 * 而专用服务器上根本不会装 Litematica。实测把 Litematica 写进 {@code depends} 之后，
 * 专用服务器直接以 {@code HARD_DEP_NO_CANDIDATE} 拒绝加载本模组——功能整个没了。
 * Fabric 的 {@code depends} 没有按环境区分的能力，因此它只能是 {@code suggests}。
 *
 * <p>于是这里做两件小事，替代原先那套 400 行的自绘降级界面：
 * <ol>
 *   <li>没装 Litematica / malilib 时给一句明确的提示，而不是让玩家点了没反应；</li>
 *   <li>装了但内部界面 API 改了签名时，兜住 {@link LinkageError} 并提示——
 *       这恰恰是原来那套降级界面<b>挡不住</b>的那种坏法（模组在、类在、签名变了）。</li>
 * </ol>
 * 这两条都只是「别把客户端打崩 + 说清楚」，不提供第二套界面。
 */
public final class LmpProgressScreens
{
    private static final String LITEMATICA = "litematica";
    private static final String MALILIB = "malilib";

    private LmpProgressScreens()
    {
    }

    /** 客户端能不能显示进度界面（服务端不需要它们）。 */
    public static boolean litematicaAvailable()
    {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded(LITEMATICA) && loader.isModLoaded(MALILIB);
    }

    public static void open(UUID regionId, String regionName)
    {
        if (!litematicaAvailable())
        {
            message("lmp.gui.status.needs_litematica");
            return;
        }

        try
        {
            GuiBase.openGui(new LmpGuiMaterialList(new LmpMaterialList(regionId, regionName)));
        }
        catch (LinkageError e)
        {
            LmpMod.LOGGER.error("Litematica 的内部界面 API 与预期不符（本模组按 0.26.x 构建），无法打开进度界面", e);
            message("lmp.gui.status.litematica_incompatible");
        }
    }

    private static void message(String key)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player != null)
        {
            client.player.sendMessage(Text.translatable(key), false);
        }
    }
}

package com.chenjicheng.lmp.client.gui;

import java.util.UUID;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;

import fi.dy.masa.malilib.gui.GuiBase;

/**
 * 打开进度界面的唯一入口：有 Litematica 就用它的材料清单界面，没有就退回自绘界面。
 *
 * <p>「有 Litematica」的判定与 {@link LmpMaterialList} 的加载必须分开：
 * 后者引用 Litematica 内部包，只有真的走那条分支时才能被加载。
 * 因此这里的引用只出现在分支体内部（JVM 按需解析常量池，不会在类加载时就去找 Litematica）。
 */
public final class LmpProgressScreens
{
    private static final boolean LITEMATICA_PRESENT = FabricLoader.getInstance().isModLoaded("litematica")
            && FabricLoader.getInstance().isModLoaded("malilib");

    private LmpProgressScreens()
    {
    }

    /** 是否在用 Litematica 的材料清单界面（诊断与提示文案用）。 */
    public static boolean usesLitematicaGui()
    {
        return LITEMATICA_PRESENT;
    }

    public static void open(UUID regionId, String regionName)
    {
        if (LITEMATICA_PRESENT)
        {
            GuiBase.openGui(new LmpGuiMaterialList(new LmpMaterialList(regionId, regionName)));
            return;
        }

        MinecraftClient.getInstance().setScreen(new LmpProgressScreen(regionId));
    }
}

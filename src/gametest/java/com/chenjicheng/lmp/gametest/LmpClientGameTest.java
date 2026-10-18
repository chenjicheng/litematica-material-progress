package com.chenjicheng.lmp.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.client.gui.LmpGuiMaterialList;
import com.chenjicheng.lmp.client.gui.LmpProgressScreen;
import com.chenjicheng.lmp.client.gui.LmpProgressScreens;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 在真实客户端上跑通两条界面路径（对应 YouTrack LMP-9）。
 *
 * <p>这是服务端 GameTest 覆盖不到的部分：界面能不能构造、能不能渲染、有没有在渲染线程之外改 GUI、
 * 监听者会不会泄漏。跑完会留下两张截图作为人工复核的证据。
 *
 * <p>数据是直接注入 {@link ClientProgressStore} 的，不经网络——本用例测的是界面本身，
 * 协议与扫描另有服务端 GameTest 覆盖。这样构造的数据也能精确控制两个进度维度的数值，
 * 断言才有意义。
 *
 * <p>本类位于独立的 gametest 源集，不进入发布产物。
 */
public class LmpClientGameTest implements FabricClientGameTest
{
    /** 三种所需材料：一种已凑齐、一种差一些、一种一个都没有；另加一种原理图不需要的额外囤积。 */
    private static final int STONE_REQUIRED = 1000;
    private static final int STONE_PRESENT = 640;
    private static final int PLANKS_REQUIRED = 500;
    private static final int PLANKS_PRESENT = 500;
    private static final int GLASS_REQUIRED = 200;
    private static final int GLASS_PRESENT = 0;
    private static final int EXTRA_DIRT = 12345;

    @Override
    public void runTest(ClientGameTestContext context)
    {
        final UUID regionId = UUID.randomUUID();

        context.runOnClient(client -> seedStore(regionId));
        context.waitTicks(2);

        verifyLitematicaPath(context, regionId);
        verifyFallbackPath(context, regionId);
        verifyNoListenerLeak(context, regionId);
    }

    /** 造一份快照：种类进度 1/3、数量进度 1140/1700，两个维度都不是 0% 或 100%。 */
    private static void seedStore(UUID regionId)
    {
        ClientProgressStore store = ClientProgressStore.get();
        store.setRegions(List.of(new LmpPayloads.RegionInfo(
                regionId, "主基地仓库", "minecraft:overworld", null,
                new BlockPos(0, 64, 0), new BlockPos(31, 70, 15),
                7L, 1_700_000_000_000L, 1000)));

        store.setProgress(regionId, 7L, (byte) 0, 1000, List.of(
                new LmpPayloads.Entry(Registries.ITEM.getId(Items.STONE), STONE_REQUIRED, STONE_PRESENT),
                new LmpPayloads.Entry(Registries.ITEM.getId(Items.OAK_PLANKS), PLANKS_REQUIRED, PLANKS_PRESENT),
                new LmpPayloads.Entry(Registries.ITEM.getId(Items.GLASS), GLASS_REQUIRED, GLASS_PRESENT),
                new LmpPayloads.Entry(Registries.ITEM.getId(Items.DIRT), 0, EXTRA_DIRT)));
    }

    /** 路径一：复用 Litematica 的材料清单界面。 */
    private static void verifyLitematicaPath(ClientGameTestContext context, UUID regionId)
    {
        if (!LmpProgressScreens.usesLitematicaGui())
        {
            throw new AssertionError("开发环境里应当装了 Litematica，否则这条路径根本没被覆盖到");
        }

        context.runOnClient(client -> LmpProgressScreens.open(regionId, "主基地仓库"));
        context.waitForScreen(LmpGuiMaterialList.class);
        context.waitTicks(5);

        context.runOnClient(client ->
        {
            if (!(client.currentScreen instanceof LmpGuiMaterialList screen))
            {
                throw new AssertionError("应打开 Litematica 材料清单界面，实际 " + client.currentScreen);
            }

            // 标题上挂着「种类进度 / 数量进度」两个维度：1/3 种类、1140/1700 数量
            String title = screen.getTitleString();
            LmpMod.LOGGER.info("[lmp-client] Litematica 路径标题：{}", title);

            if (!title.contains("33.3"))
            {
                throw new AssertionError("标题应含种类进度 33.3%，实际：" + title);
            }

            if (!title.contains("67.1"))
            {
                throw new AssertionError("标题应含数量进度 67.1%（1140/1700），实际：" + title);
            }
        });

        Path shot = context.takeScreenshot("lmp-progress-litematica");
        LmpMod.LOGGER.info("[lmp-client] Litematica 路径截图：{}", shot);
    }

    /** 路径二：没装 Litematica 时用的自绘界面。 */
    private static void verifyFallbackPath(ClientGameTestContext context, UUID regionId)
    {
        context.runOnClient(client -> client.setScreen(new LmpProgressScreen(regionId)));
        context.waitForScreen(LmpProgressScreen.class);
        context.waitTicks(5);

        context.runOnClient(client ->
        {
            if (!(client.currentScreen instanceof LmpProgressScreen))
            {
                throw new AssertionError("应打开自绘界面，实际 " + client.currentScreen);
            }
        });

        Path shot = context.takeScreenshot("lmp-progress-fallback");
        LmpMod.LOGGER.info("[lmp-client] 自绘路径截图：{}", shot);

        context.runOnClient(client -> client.setScreen(null));
    }

    /** 反复开关界面后监听者必须回到 0：否则每开一次界面就泄漏一个监听者。 */
    private static void verifyNoListenerLeak(ClientGameTestContext context, UUID regionId)
    {
        for (int i = 0; i < 3; i++)
        {
            context.runOnClient(client -> LmpProgressScreens.open(regionId, "主基地仓库"));
            context.waitForScreen(LmpGuiMaterialList.class);
            context.waitTicks(2);
            context.runOnClient(client -> client.setScreen(null));
            context.waitTicks(2);

            context.runOnClient(client -> client.setScreen(new LmpProgressScreen(regionId)));
            context.waitForScreen(LmpProgressScreen.class);
            context.waitTicks(2);
            context.runOnClient(client -> client.setScreen(null));
            context.waitTicks(2);
        }

        context.runOnClient(client ->
        {
            int listeners = ClientProgressStore.get().listenerCount();

            if (listeners != 0)
            {
                throw new AssertionError("反复开关界面后监听者应回到 0，实际 " + listeners);
            }
        });
    }
}

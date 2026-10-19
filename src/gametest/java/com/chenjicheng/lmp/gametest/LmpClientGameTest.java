package com.chenjicheng.lmp.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.util.StringUtils;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.client.gui.LmpGuiMaterialList;
import com.chenjicheng.lmp.client.gui.LmpMaterialList;
import com.chenjicheng.lmp.client.gui.LmpProgressScreens;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 在真实客户端上跑通进度界面（对应 YouTrack LMP-9）。
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

        // 必须进一个真实世界让 client.player 不为 null：
        // 上游 GuiMaterialList 的构造函数会调 updateAvailableCounts(getMaterialsAll(), player)，
        // 而它在 player == null 时直接返回——站在标题界面上根本测不到这个覆盖行为。
        try (TestSingleplayerContext singleplayer = LmpTestWorlds.create(context))
        {
            context.waitFor(client -> client.player != null);
            context.waitTicks(20);

            // 两个客户端用例在同一个 JVM 里顺序跑，共用同一个单例缓存：
            // 先清一次，监听者计数才是本用例自己的
            context.runOnClient(client -> ClientProgressStore.get().reset());
            context.runOnClient(client -> seedStore(regionId));
            context.waitTicks(2);

            verifyProgressScreen(context, regionId);
            verifyNoListenerLeak(context, regionId);
        }
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

    /** 进度界面就是 Litematica 的材料清单界面（Litematica 是硬依赖，没有第二条路径）。 */
    private static void verifyProgressScreen(ClientGameTestContext context, UUID regionId)
    {
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

            // 服务端按全量原理图统计，界面必须标注，否则玩家拿自己的材料清单一对就会当成缺陷。
            // 断言用翻译键而不是硬编码英文：这个用例不该因为客户端语言换了就失败。
            String fullSchematicNote = StringUtils.translate("lmp.gui.status.full_schematic");

            if (!title.contains(fullSchematicNote))
            {
                throw new AssertionError("标题应标注「所需按全量原理图统计」（" + fullSchematicNote + "），实际：" + title);
            }

            // 关键一条：上游会用玩家背包数量覆盖 Available 列（玩家背包是空的 → 全是 0）。
            // 本模组重写 getMaterialsAll() 返回独立副本挡掉了它，因此界面显示的必须仍是区域里的数量。
            LmpMaterialList list = screen.materialList();
            int stone = list.displayedCollected(Items.STONE);
            int dirt = list.displayedCollected(Items.DIRT);

            if (stone != STONE_PRESENT)
            {
                throw new AssertionError("Available 列应显示区域内已收集 " + STONE_PRESENT
                        + "，实际 " + stone + "（若为 0 说明被玩家背包数量覆盖了）");
            }

            if (dirt != EXTRA_DIRT)
            {
                throw new AssertionError("额外囤积应显示 " + EXTRA_DIRT + "，实际 " + dirt);
            }
        });

        Path shot = context.takeScreenshot("lmp-progress");
        LmpMod.LOGGER.info("[lmp-client] 进度界面截图：{}", shot);

        context.runOnClient(client -> client.setScreen(null));
        context.waitTicks(2);
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

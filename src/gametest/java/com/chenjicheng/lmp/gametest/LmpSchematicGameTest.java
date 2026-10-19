package com.chenjicheng.lmp.gametest;

import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.joml.Vector2i;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.Syncmatica;
import ch.endte.syncmatica.data.ServerPlacement;
import ch.endte.syncmatica.litematica.LitematicManager;

import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.client.gui.LmpGuiMaterialList;
import com.chenjicheng.lmp.client.gui.LmpGuiPlacementSettings;
import com.chenjicheng.lmp.client.gui.LmpPlacementEntry;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 端到端用例：**真实的**共享原理图 → 放置行上的入口 → 设置界面 → 保存 → 服务端解析原理图。
 *
 * <p>它补上另外两条用例够不到的两件事：
 * <ol>
 *   <li>入口按钮挂在「已共享」的放置行上。前一条用例只检查了注入点是否存在；这里让 Syncmatica
 *       真的把一个放置渲染出来，再从放置列表里点按钮。</li>
 *   <li>服务端解析的是 <b>Litematica 自己写出的</b> {@code .litematic}（见 {@link LmpSchematicFixture}），
 *       而不是手写 NBT。这正好补上 docs/design.md 里「未与真实原理图对拍」那个缺口。</li>
 * </ol>
 *
 * <p>断言同时覆盖两侧的口径：所需来自原理图（25 石头 + 2 木板 + 1 箱子），
 * 已收集来自材料区里的箱子（7 石头），因此任何一侧算错都会让用例失败。
 *
 * <p>本类位于独立的 gametest 源集，不进入发布产物。
 */
public class LmpSchematicGameTest implements FabricClientGameTest
{
    private static final String SCHEMATIC_NAME = "lmp-e2e";
    /** 3×3×3 = 27 格：2 格木板 + 1 格箱子，其余都是石头。 */
    private static final int STONE_BLOCKS = 27 - 2 - 1;
    private static final int PLANK_BLOCKS = 2;
    private static final int CHEST_CONTENT = 7;

    @Override
    public void runTest(ClientGameTestContext context)
    {
        final UUID placementId = UUID.randomUUID();

        try (TestSingleplayerContext singleplayer = LmpTestWorlds.create(context))
        {
            context.waitFor(client -> client.player != null);
            context.waitTicks(20);
            context.runOnClient(client -> ClientProgressStore.get().reset());

            String dimension = context.computeOnClient(client -> client.world.getRegistryKey().getValue().toString());
            BlockPos[] area = context.computeOnClient(client ->
            {
                BlockPos base = client.player.getBlockPos().add(2, 0, 2);
                return new BlockPos[] { base, base.add(2, 2, 2) };
            });

            // 在服务端摆结构，再用 Litematica 自己的写入器把这块区域写成原理图
            LitematicaSchematic schematic = singleplayer.getServer().computeOnServer(server ->
            {
                ServerWorld world = server.getOverworld();
                buildKnownStructure(world, area);
                return LmpSchematicFixture.capture(world, area[0], area[1], SCHEMATIC_NAME);
            });

            if (schematic == null)
            {
                throw new AssertionError("Litematica 没能从世界里生成原理图，后续断言没有意义");
            }

            shareOnBothSides(context, singleplayer, schematic, placementId, dimension, area[0]);
            context.waitTicks(5);

            // 诊断：共享放置要被渲染成 Litematica 的放置，并且上游要算出包围盒——
            // 设置界面的「用原理图范围」直接取它
            context.runOnClient(client ->
            {
                var placements = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager()
                        .getAllSchematicsPlacements();

                if (placements.isEmpty())
                {
                    throw new AssertionError("Syncmatica 没有把共享放置渲染成 Litematica 的放置");
                }

                var placement = placements.get(0);
                int subRegions = placement.getSubRegionBoxes(
                        fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.ANY).size();
                LmpMod.LOGGER.info("[lmp-e2e] 放置 {}：子区域 {} 个，enclosingBox={}",
                        placement.getName(), subRegions, placement.getEclosingBox());

                if (subRegions == 0)
                {
                    throw new AssertionError("渲染出来的放置没有任何子区域，界面取不到范围");
                }
            });

            // 放置列表里应当出现这一行，并且我们的 mixin 在它上面挂出了按钮
            context.setScreen(GuiSchematicPlacementsList::new);
            context.waitForScreen(GuiSchematicPlacementsList.class);
            context.waitTicks(5);

            int[] button = context.computeOnClient(client -> LmpPlacementEntry.lastAttachedBounds());

            if (button == null)
            {
                throw new AssertionError("已共享的放置行上应当挂出材料进度按钮"
                        + "（mixin 没生效，或者这个放置没被 Syncmatica 认成共享放置）");
            }

            clickAt(context, button[0] + button[2] / 2, button[1] + button[3] / 2);
            context.waitForScreen(LmpGuiPlacementSettings.class);
            context.waitTicks(2);

            verifySetupAndSave(context, placementId, area);
            verifyRequiredMaterials(context, area);
        }
    }

    // ------------------------------------------------------------ 结构

    /** 已知结构：一堆石头、两块木板，以及一个装着石头的箱子。 */
    private static void buildKnownStructure(World world, BlockPos[] area)
    {
        BlockPos min = area[0];
        BlockPos max = area[1];

        for (int x = min.getX(); x <= max.getX(); x++)
        {
            for (int y = min.getY(); y <= max.getY(); y++)
            {
                for (int z = min.getZ(); z <= max.getZ(); z++)
                {
                    world.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState());
                }
            }
        }

        world.setBlockState(max, Blocks.OAK_PLANKS.getDefaultState());
        world.setBlockState(max.add(-1, 0, 0), Blocks.OAK_PLANKS.getDefaultState());

        // 角上放箱子：它是材料区里唯一的容器，内容物就是「已收集」那一侧
        world.setBlockState(min, Blocks.CHEST.getDefaultState());

        if (world.getBlockEntity(min) instanceof ChestBlockEntity chest)
        {
            chest.setStack(0, new ItemStack(Items.STONE, CHEST_CONTENT));
        }
        else
        {
            throw new AssertionError("放下箱子之后没有方块实体");
        }
    }

    // ------------------------------------------------------------ 共享放置

    /** 服务端与客户端的 Syncmatica 上下文各登记一份，客户端再把它渲染成 Litematica 的放置。 */
    private static void shareOnBothSides(ClientGameTestContext context, TestSingleplayerContext singleplayer,
                                         LitematicaSchematic schematic, UUID placementId, String dimension,
                                         BlockPos origin)
    {
        singleplayer.getServer().runOnServer(server ->
        {
            Context shared = Syncmatica.getContext(Syncmatica.SERVER_CONTEXT);

            if (shared == null || !shared.isServer())
            {
                throw new AssertionError("集成服务端没有 Syncmatica 服务端上下文，无法测试共享放置");
            }

            share(shared, schematic, placementId, dimension, origin);
        });

        context.runOnClient(client ->
        {
            Context clientContext = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);

            if (clientContext == null || !clientContext.isStarted())
            {
                throw new AssertionError("客户端 Syncmatica 上下文尚未启动，无法测试共享放置");
            }

            ServerPlacement placement = share(clientContext, schematic, placementId, dimension, origin);
            LitematicManager.getInstance().renderSyncmatic(placement);
        });
    }

    /** 写文件可能失败：把受检异常换成断言，免得整个用例方法都要声明 throws。 */
    private static ServerPlacement share(Context context, LitematicaSchematic schematic, UUID placementId,
                                         String dimension, BlockPos origin)
    {
        try
        {
            return LmpSchematicFixture.share(context, schematic, placementId, dimension, origin);
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError("写入测试用原理图失败", e);
        }
    }

    // ------------------------------------------------------------ 设置界面 → 保存

    private static void verifySetupAndSave(ClientGameTestContext context, UUID placementId, BlockPos[] area)
    {
        context.runOnClient(client ->
        {
            LmpGuiPlacementSettings screen = screen(client);

            if (screen.regionId() != null)
            {
                throw new AssertionError("这个放置一开始不该有材料区");
            }
        });

        click(context, "lmp.gui.setup.use_schematic_bounds");

        context.runOnClient(client ->
        {
            if (!screen(client).draft().rangeSet())
            {
                throw new AssertionError("点了「用原理图范围」之后草稿应当有范围（按钮可能是灰的）");
            }
        });

        click(context, "lmp.gui.setup.save");

        context.waitFor(client -> ClientProgressStore.get().regionForPlacement(placementId).isPresent());

        context.runOnClient(client ->
        {
            LmpPayloads.RegionInfo region = ClientProgressStore.get().regionForPlacement(placementId).orElseThrow();

            if (!region.pos1().equals(area[0]) || !region.pos2().equals(area[1]))
            {
                throw new AssertionError("材料区应当等于原理图包围盒，实际 " + region.pos1() + "→" + region.pos2());
            }

            if (screen(client).regionId() == null)
            {
                throw new AssertionError("保存成功后界面应当接住这个区域");
            }
        });

        LmpMod.LOGGER.info("[lmp-e2e] 设置界面截图：{}", context.takeScreenshot("lmp-e2e-setup"));
    }

    // ------------------------------------------------------------ 服务端解析原理图

    /**
     * 打开进度界面，等「所需材料」真的从原理图里算出来。
     *
     * <p>这条断言同时验证三件事：扫描找到了箱子里的 7 个石头、服务端按 placementId 找到了
     * Syncmatica 的 {@code .litematic}、解析结果与 Litematica 自己写出的方块数一致。
     */
    private static void verifyRequiredMaterials(ClientGameTestContext context, BlockPos[] area)
    {
        click(context, "lmp.gui.setup.open_progress");
        context.waitForScreen(LmpGuiMaterialList.class);

        UUID regionId = context.computeOnClient(client ->
                ClientProgressStore.get().regions().get(0).id());

        context.waitFor(client -> ClientProgressStore.get().progress(regionId)
                .map(MaterialProgress::hasRequired)
                .orElse(false), 1_200);
        context.waitTicks(2);

        context.runOnClient(client ->
        {
            MaterialProgress progress = ClientProgressStore.get().progress(regionId).orElseThrow();

            assertRequired(progress, Items.STONE, STONE_BLOCKS);
            assertRequired(progress, Items.OAK_PLANKS, PLANK_BLOCKS);
            assertRequired(progress, Items.CHEST, 1);

            if (progress.presentFor(Items.STONE) != CHEST_CONTENT)
            {
                throw new AssertionError("材料区里应当统计到 " + CHEST_CONTENT
                        + " 个石头，实际 " + progress.presentFor(Items.STONE));
            }
        });

        LmpMod.LOGGER.info("[lmp-e2e] 进度界面截图：{}", context.takeScreenshot("lmp-e2e-progress"));
    }

    private static void assertRequired(MaterialProgress progress, Item item, int expected)
    {
        Integer actual = progress.required().get(item);

        if (actual == null || actual != expected)
        {
            throw new AssertionError("所需材料 " + item + " 应为 " + expected + "，实际 " + actual
                    + "（全部：" + progress.required() + "）");
        }
    }

    // ------------------------------------------------------------ 工具

    private static LmpGuiPlacementSettings screen(MinecraftClient client)
    {
        if (client.currentScreen instanceof LmpGuiPlacementSettings screen)
        {
            return screen;
        }

        throw new AssertionError("当前界面应当是材料区设置界面，实际 " + client.currentScreen);
    }

    private static void click(ClientGameTestContext context, String translationKey)
    {
        Vector2i target = context.computeOnClient(client ->
        {
            LmpGuiPlacementSettings screen = screen(client);
            var button = screen.buttonFor(translationKey);

            if (button == null)
            {
                throw new AssertionError("界面上没有这个按钮：" + translationKey);
            }

            return guiToWindow(client, button.getX() + button.getWidth() / 2,
                    button.getY() + button.getHeight() / 2);
        });

        clickAt(context, target);
    }

    private static void clickAt(ClientGameTestContext context, int guiX, int guiY)
    {
        Vector2i target = context.computeOnClient(client -> guiToWindow(client, guiX, guiY));
        clickAt(context, target);
    }

    private static void clickAt(ClientGameTestContext context, Vector2i target)
    {
        context.getInput().setCursorPos(target.x, target.y);
        context.getInput().pressMouse(0);
        context.getInput().releaseMouse(0);
        context.waitTicks(2);
    }

    private static Vector2i guiToWindow(MinecraftClient client, int guiX, int guiY)
    {
        double scale = client.getWindow().getScaleFactor();
        return new Vector2i((int) (guiX * scale), (int) (guiY * scale));
    }
}

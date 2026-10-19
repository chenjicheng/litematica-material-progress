package com.chenjicheng.lmp.gametest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.joml.Vector2i;

import fi.dy.masa.malilib.gui.button.ButtonBase;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.LmpServer;
import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.client.gui.LmpGuiPlacementSettings;
import com.chenjicheng.lmp.net.LmpPayloads;
import com.chenjicheng.lmp.region.MaterialRegion;

/**
 * 在真实客户端里跑通「设置界面 → 取范围 → 保存 → 服务端落库」这条端到端路径。
 *
 * <p>这是服务端 GameTest 覆盖不到的部分：界面能不能构造、按钮点下去有没有反应、
 * 新协议能不能被服务端接住、广播回来的区域清单对不对得上。
 *
 * <p>数据是直接喂给界面的（一个合成的 placementId 与包围盒），不需要真的加载一份共享原理图——
 * 界面只认纯数据，这正是它可测的原因。跑完会留下一张设置界面的截图。
 *
 * <p>本类位于独立的 gametest 源集，不进入发布产物。
 */
public class LmpClientSetupGameTest implements FabricClientGameTest
{
    private static final String REGION_NAME = "主基地仓库";

    @Override
    public void runTest(ClientGameTestContext context)
    {
        final UUID placementId = UUID.randomUUID();

        // 必须进一个真实世界：保存要发 payload，而 payload 需要一个连着的服务端；
        // 同时「用当前选区」这类按钮的可用状态也取决于玩家在不在对应维度。
        // 必须开作弊：改材料区要求权限等级 2（与 /lmp 命令同一道闸），而未开作弊的单人世界主机
        // 权限等级是 0——服务端会明确回一句「你没有修改材料区的权限」，用例就卡在等广播上。
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(creator -> creator.setCheatsEnabled(true))
                .create())
        {
            context.waitFor(client -> client.player != null);
            context.waitTicks(20);

            // 同上：与另一个客户端用例共用单例缓存，先清一次再断言监听者计数
            context.runOnClient(client -> ClientProgressStore.get().reset());

            Identifier dimension = context.computeOnClient(client -> client.world.getRegistryKey().getValue());
            BlockPos[] bounds = context.computeOnClient(client ->
            {
                BlockPos base = client.player.getBlockPos();
                return new BlockPos[] { base.add(-4, -2, -4), base.add(4, 2, 4) };
            });

            context.setScreen(() -> new LmpGuiPlacementSettings(placementId, REGION_NAME, dimension, bounds));
            context.waitForScreen(LmpGuiPlacementSettings.class);
            context.waitTicks(2);

            // 未划定材料区时界面必须说清楚，而不是空白
            context.runOnClient(client ->
            {
                LmpGuiPlacementSettings screen = screen(client);

                if (screen.regionId() != null)
                {
                    throw new AssertionError("这个合成 placement 一开始不该有材料区");
                }

                if (screen.draft().rangeSet())
                {
                    throw new AssertionError("打开界面时范围应当是未设定");
                }
            });

            verifyBoundsButton(context, bounds);
            verifySaveButton(context, singleplayer, placementId, dimension, bounds);
            verifyDeleteButton(context, singleplayer, placementId);
            verifyPlacementRowHook(context);
            verifyNoListenerLeak(context, placementId, dimension, bounds);
        }
    }

    /** 「用原理图范围」把两个角点填成原理图包围盒。 */
    private static void verifyBoundsButton(ClientGameTestContext context, BlockPos[] bounds)
    {
        click(context, "lmp.gui.setup.use_schematic_bounds");

        context.runOnClient(client ->
        {
            BlockPos[] corners = screen(client).draft().corners()
                    .orElseThrow(() -> new AssertionError("点了「用原理图范围」之后应当有范围"));

            if (!corners[0].equals(bounds[0]) || !corners[1].equals(bounds[1]))
            {
                throw new AssertionError("范围应当等于原理图包围盒 " + bounds[0] + "→" + bounds[1]
                        + "，实际 " + corners[0] + "→" + corners[1]);
            }
        });

        LmpMod.LOGGER.info("[lmp-client] 设置界面截图：{}", context.takeScreenshot("lmp-setup"));
    }

    /** 保存：客户端发 payload，服务端校验后落库并广播回来。 */
    private static void verifySaveButton(ClientGameTestContext context, TestSingleplayerContext singleplayer,
                                         UUID placementId, Identifier dimension, BlockPos[] bounds)
    {
        click(context, "lmp.gui.setup.save");

        // 服务端处理完会把新的区域清单广播回来；客户端缓存出现这条区域就说明整条链路通了
        context.waitFor(client -> ClientProgressStore.get().regionForPlacement(placementId).isPresent());

        context.runOnClient(client ->
        {
            LmpPayloads.RegionInfo region = ClientProgressStore.get().regionForPlacement(placementId)
                    .orElseThrow(() -> new AssertionError("客户端缓存里应当出现刚保存的材料区"));

            if (!REGION_NAME.equals(region.name()))
            {
                throw new AssertionError("区域名应为 " + REGION_NAME + "，实际 " + region.name());
            }

            if (!dimension.toString().equals(region.dimension()))
            {
                throw new AssertionError("维度应为 " + dimension + "，实际 " + region.dimension());
            }

            if (!region.pos1().equals(bounds[0]) || !region.pos2().equals(bounds[1]))
            {
                throw new AssertionError("服务端存下的范围应当与界面一致，实际 " + region.pos1() + "→" + region.pos2());
            }

            if (screen(client).regionId() == null)
            {
                throw new AssertionError("保存成功后界面应当接住这个区域（删除与打开进度都靠它）");
            }
        });

        // 客户端缓存只是广播的结果，真正的判据是服务端的存储
        singleplayer.getServer().runOnServer(server ->
        {
            MaterialRegion stored = LmpServer.store().orElseThrow().byPlacement(placementId)
                    .orElseThrow(() -> new AssertionError("服务端应当已按 placement 存下这个材料区"));

            if (!REGION_NAME.equals(stored.name()))
            {
                throw new AssertionError("服务端存的区域名不对：" + stored.name());
            }

            if (!stored.pos1().equals(bounds[0]) || !stored.pos2().equals(bounds[1]))
            {
                throw new AssertionError("服务端存的范围不对：" + stored.pos1() + "→" + stored.pos2());
            }
        });
    }

    /** 删除：服务端移除之后，客户端缓存里的幽灵条目也必须消失。 */
    private static void verifyDeleteButton(ClientGameTestContext context, TestSingleplayerContext singleplayer,
                                           UUID placementId)
    {
        // 服务端对同一玩家的区域编辑有 1 秒冷却（一次编辑要同步重写整个 regions.json）。
        // 真实玩家不会在两帧里连点保存和删除，这里如实等过冷却。
        context.waitTicks(25);

        click(context, "lmp.gui.setup.delete");

        context.waitFor(client -> ClientProgressStore.get().regionForPlacement(placementId).isEmpty());

        singleplayer.getServer().runOnServer(server ->
        {
            if (LmpServer.store().orElseThrow().byPlacement(placementId).isPresent())
            {
                throw new AssertionError("删除之后服务端不该还留着这个材料区");
            }
        });
    }

    /**
     * 反复开关设置界面后监听者必须回到 0。
     *
     * <p>设置界面在 initGui() 里重建按钮与输入框，而 initGui 会被刷新与窗口缩放反复调用；
     * 监听者一旦跟着 initGui 注册，回调次数就会随开关次数增长，而关闭界面只注销得掉一个。
     */
    private static void verifyNoListenerLeak(ClientGameTestContext context, UUID placementId, Identifier dimension,
                                             BlockPos[] bounds)
    {
        for (int i = 0; i < 3; i++)
        {
            context.setScreen(() -> new LmpGuiPlacementSettings(placementId, REGION_NAME, dimension, bounds));
            context.waitForScreen(LmpGuiPlacementSettings.class);
            context.waitTicks(2);
            context.runOnClient(client -> screen(client).refreshView());
            context.waitTicks(2);
            context.runOnClient(client -> client.setScreen(null));
            context.waitTicks(2);
        }

        context.runOnClient(client ->
        {
            int listeners = ClientProgressStore.get().listenerCount();

            if (listeners != 0)
            {
                throw new AssertionError("反复开关设置界面后监听者应回到 0，实际 " + listeners);
            }
        });
    }

    /**
     * 主要入口是挂在 Litematica 放置行上的按钮，而那是上游的内部结构。
     *
     * <p>这里不构造真实放置行（需要一份共享原理图与 Syncmatica 的上下文，代价远超收益），
     * 而是检查两件会静默失效的事：mixin 有没有真的合并进放置行类、注入点（构造函数）还在不在。
     * 上游结构一变，注入点用 {@code require = 0} 会安静地跳过——这条用例就是那个「安静」的探测器。
     *
     * <p><b>仍未覆盖</b>：按钮在共享原理图行上到底长什么样、点下去是否真的打开设置界面。
     * 那需要真实的共享原理图，见 docs/design.md 的已知限制。
     */
    private static void verifyPlacementRowHook(ClientGameTestContext context)
    {
        context.runOnClient(client ->
        {
            Class<?> row;

            try
            {
                row = Class.forName("fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement");
            }
            catch (ClassNotFoundException e)
            {
                throw new AssertionError("找不到放置行类，上游结构已变：按钮不会出现", e);
            }

            boolean merged = Arrays.stream(row.getDeclaredMethods())
                    .map(Method::getName)
                    .anyMatch(name -> name.contains("lmp$addMaterialButton"));

            if (!merged)
            {
                throw new AssertionError("mixin 没有合并进放置行类，按钮不会出现。已声明的方法："
                        + Arrays.toString(Arrays.stream(row.getDeclaredMethods()).map(Method::getName).toArray()));
            }

            List<String> expected = List.of("int", "int", "int", "int", "boolean",
                    "fi.dy.masa.litematica.schematic.placement.SchematicPlacement", "int",
                    "fi.dy.masa.litematica.gui.widgets.WidgetListSchematicPlacements");
            boolean injectable = Arrays.stream(row.getDeclaredConstructors())
                    .anyMatch(ctor -> Arrays.asList(ctor.getParameterTypes()).stream()
                            .map(Class::getName).toList().equals(expected));

            if (!injectable)
            {
                throw new AssertionError("放置行的构造函数签名已变，注入点找不到：按钮不会出现。实际构造函数："
                        + Arrays.toString(Arrays.stream(row.getDeclaredConstructors())
                        .map(Constructor::getParameterTypes).map(Arrays::toString).toArray()));
            }

            LmpMod.LOGGER.info("[lmp-client] 放置行 hook 校验通过：mixin 已合并，注入点仍在");
        });
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

    /**
     * 真的用鼠标点这个按钮。
     *
     * <p>不直接调界面方法：要验证的正是「按钮在、能点到、点下去有反应」这一段，
     * 绕过去就只剩下一半的覆盖。坐标要乘上 GUI 缩放，输入层收的是窗口坐标。
     */
    private static void click(ClientGameTestContext context, String translationKey)
    {
        Vector2i target = context.computeOnClient(client ->
        {
            ButtonBase button = screen(client).buttonFor(translationKey);

            if (button == null)
            {
                throw new AssertionError("界面上没有这个按钮：" + translationKey);
            }

            double scale = client.getWindow().getScaleFactor();
            return new Vector2i(
                    (int) ((button.getX() + button.getWidth() / 2.0D) * scale),
                    (int) ((button.getY() + button.getHeight() / 2.0D) * scale));
        });

        context.getInput().setCursorPos(target.x, target.y);
        context.getInput().pressMouse(0);
        context.getInput().releaseMouse(0);
        context.waitTicks(2);
    }
}

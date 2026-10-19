package com.chenjicheng.lmp.client.gui;

import java.util.Optional;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import fi.dy.masa.malilib.util.StringUtils;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.client.LmpPlacementBridge;
import com.chenjicheng.lmp.mixin.LmpWidgetContainerInvoker;

import org.jetbrains.annotations.Nullable;

/**
 * 放置行上的「材料」按钮：把上游的放置翻译成纯数据，再挂一个按钮上去。
 *
 * <p>上游的 {@code SchematicPlacement} 只在方法签名里传进传出，真正读它的地方是
 * {@link LmpPlacementBridge}；往下传给界面的已经是 {@code placementId + 名字 + 维度 + 包围盒}，
 * 设置界面因此不依赖 Litematica。
 *
 * <p><b>绝不抛异常</b>：这段代码跑在放置列表逐行渲染的路径上。任何异常（上游 API 变了、
 * Syncmatica 的上下文还没起来）都只能让这一个按钮不出现，不能把整个界面拖垮。
 */
public final class LmpPlacementEntry
{
    /**
     * 最近一次挂上的按钮的位置（x, y, width, height）。
     *
     * <p>供诊断与客户端 GameTest 使用：按钮挂在放置行内部，外面拿不到它。存坐标而不是控件引用，
     * 免得把已经关掉的界面留在内存里。
     */
    private static volatile int[] lastAttachedBounds;

    private LmpPlacementEntry()
    {
    }

    /** 最近一次挂上的按钮的位置；还没挂过时为 {@code null}。 */
    public static int[] lastAttachedBounds()
    {
        int[] bounds = lastAttachedBounds;
        return bounds == null ? null : bounds.clone();
    }

    /**
     * 给一行放置挂上入口按钮。
     *
     * @param container 该行的容器（{@code WidgetSchematicPlacement} 本身）
     * @param x         按钮右边缘的 x（上游的 {@code buttonsStartX}）
     * @param y         行的 y
     * @return 挂上的按钮；不是共享放置或出问题时为 {@code null}
     */
    public static @Nullable ButtonGeneric attach(WidgetContainer container, int x, int y, SchematicPlacement placement)
    {
        try
        {
            Optional<LmpPlacementBridge.Shared> shared = LmpPlacementBridge.describe(placement);

            if (shared.isEmpty())
            {
                return null;
            }

            LmpPlacementBridge.Shared data = shared.get();
            ButtonGeneric button = new ButtonGeneric(x, y + 1, -1, true, "lmp.gui.setup.button");
            button.setHoverStrings(StringUtils.translate("lmp.gui.setup.button.hover", data.name()));

            ((LmpWidgetContainerInvoker) container).lmp$addButton(button, (clicked, mouseButton) ->
                    LmpProgressScreens.openSettings(data.placementId(), data.name(), data.dimension(), data.bounds()));

            lastAttachedBounds = new int[] { button.getX(), button.getY(), button.getWidth(), button.getHeight() };
            return button;
        }
        catch (RuntimeException | LinkageError e)
        {
            LmpMod.LOGGER.warn("放置「{}」上挂材料进度按钮失败，该行不显示入口：{}",
                    placement.getName(), e.toString());
            return null;
        }
    }
}

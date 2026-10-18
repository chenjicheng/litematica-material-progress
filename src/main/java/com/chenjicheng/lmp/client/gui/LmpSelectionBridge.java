package com.chenjicheng.lmp.client.gui;

import java.util.List;
import java.util.Optional;

import net.minecraft.util.math.BlockPos;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;

/**
 * 读取 Litematica 当前选区的桥接层。
 *
 * <p>只做一件事：把「玩家用 Litematica 选区工具框好的范围」翻译成两个角点，
 * 之后的校验、落库、绑定全部走服务端命令——客户端不持有材料区的真相。
 *
 * <p>与 {@link LmpMaterialList} 一样，本类只能在 Litematica 存在时加载。
 * 另外 Litematica <b>没有</b>选区变更的公开监听器（placement 有，selection 没有），
 * 所以这里只能是「按下按钮时读一次快照」，不做持续跟随。
 */
public final class LmpSelectionBridge
{
    private LmpSelectionBridge()
    {
    }

    /** 当前选区的包围盒（多子区域时取并集）；没有选区或角点不全时返回空。 */
    public static Optional<BlockPos[]> currentBounds()
    {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

        if (selection == null)
        {
            return Optional.empty();
        }

        List<Box> boxes = selection.getAllSubRegionBoxes();
        BlockPos min = null;
        BlockPos max = null;

        for (Box box : boxes)
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            // Litematica 的 Box 允许只设了一个角点，这种半成品选区不能当成范围用
            if (pos1 == null || pos2 == null)
            {
                continue;
            }

            BlockPos low = new BlockPos(Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()));
            BlockPos high = new BlockPos(Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()), Math.max(pos1.getZ(), pos2.getZ()));

            min = min == null ? low : new BlockPos(Math.min(min.getX(), low.getX()),
                    Math.min(min.getY(), low.getY()), Math.min(min.getZ(), low.getZ()));
            max = max == null ? high : new BlockPos(Math.max(max.getX(), high.getX()),
                    Math.max(max.getY(), high.getY()), Math.max(max.getZ(), high.getZ()));
        }

        return min == null ? Optional.empty() : Optional.of(new BlockPos[] { min, max });
    }
}

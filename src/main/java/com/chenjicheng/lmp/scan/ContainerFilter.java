package com.chenjicheng.lmp.scan;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;

/**
 * 材料区里哪些容器算「已收集」。
 *
 * <p>已确认口径：只统计箱子类——箱子、陷阱箱（{@code TrappedChestBlockEntity} 继承自
 * {@link ChestBlockEntity}，因此自动覆盖）、木桶，以及<b>放置在地上的潜影盒</b>
 * （它同样是储物容器，玩家常把材料直接存在里面；不统计它会导致漏算，因此纳入并在文档中标注）。
 *
 * <p>不统计漏斗、发射器、熔炉、运输矿车等：它们通常不是材料仓库，且会显著增加扫描成本。
 * 末影箱内容因人而异，也排除在外。
 */
public final class ContainerFilter
{
    private ContainerFilter()
    {
    }

    /** 该方块实体是否计入材料区的「已收集」。 */
    public static boolean isCounted(BlockEntity blockEntity)
    {
        return blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }
}

package com.chenjicheng.lmp.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MaterialRegion} 的边界回归：坐标跨度在 int 域里会溢出，
 * 而「溢出成 0」会让超大区域通过校验，最终在分配区块数组时炸掉服务端线程。
 */
class MaterialRegionTest
{
    private static MaterialRegion region(BlockPos pos1, BlockPos pos2)
    {
        return new MaterialRegion(UUID.randomUUID(), "t", Identifier.of("minecraft", "overworld"),
                pos1, pos2, null, null, 0L, 0L, 0L, 0);
    }

    @Test
    @DisplayName("横跨整个 int 坐标范围的区域必须被拒绝，而不是算成体积 0")
    void fullCoordinateRangeIsRejected()
    {
        MaterialRegion region = region(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0));

        // 旧实现：(max - min + 1) 在 int 域回绕成 0 → 体积 0 → 校验通过 → chunks() 分配负长度数组
        assertTrue(region.volume() > MaterialRegion.MAX_VOLUME, "体积必须反映真实跨度，实际 " + region.volume());
        assertEquals(MaterialRegion.Invalid.TOO_LARGE, region.validate().orElse(null));
        assertThrows(IllegalStateException.class, region::chunks);
    }

    @Test
    @DisplayName("单轴跨度溢出但体积恰好不大的组合也不能通过")
    void overflowedAxisIsRejected()
    {
        MaterialRegion region = region(new BlockPos(Integer.MIN_VALUE, 0, Integer.MIN_VALUE),
                new BlockPos(Integer.MAX_VALUE, 0, Integer.MAX_VALUE));

        assertEquals(MaterialRegion.Invalid.TOO_LARGE, region.validate().orElse(null));
    }

    @Test
    @DisplayName("区块数超上限被拒绝")
    void tooManyChunksIsRejected()
    {
        // 16×16 区块 = 256×256 格，正好在上限；再加一格就越界
        MaterialRegion atLimit = region(new BlockPos(0, 0, 0), new BlockPos(255, 0, 255));
        assertEquals(256L, atLimit.chunkCount());
        assertTrue(atLimit.validate().isEmpty(), "恰好 256 个区块应当通过");

        MaterialRegion over = region(new BlockPos(0, 0, 0), new BlockPos(256, 0, 255));
        assertEquals(MaterialRegion.Invalid.TOO_MANY_CHUNKS, over.validate().orElse(null));
    }

    @Test
    @DisplayName("角点顺序不影响结果：pos1/pos2 归一化为 min/max")
    void cornersAreNormalised()
    {
        MaterialRegion forward = region(new BlockPos(1, 2, 3), new BlockPos(11, 12, 13));
        MaterialRegion backward = region(new BlockPos(11, 12, 13), new BlockPos(1, 2, 3));

        assertEquals(forward.pos1(), backward.pos1());
        assertEquals(forward.pos2(), backward.pos2());
        assertEquals(forward.volume(), backward.volume());
        assertEquals(11L * 11L * 11L, forward.volume());
    }

    @Test
    @DisplayName("名字为空或空白被拒绝")
    void blankNameIsRejected()
    {
        MaterialRegion blank = new MaterialRegion(UUID.randomUUID(), "   ", Identifier.of("minecraft", "overworld"),
                BlockPos.ORIGIN, BlockPos.ORIGIN, null, null, 0L, 0L, 0L, 0);

        assertEquals(MaterialRegion.Invalid.EMPTY_NAME, blank.validate().orElse(null));
    }
}

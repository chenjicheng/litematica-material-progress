package com.chenjicheng.lmp.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaterialProgressTest
{
    @BeforeAll
    static void bootstrapMinecraft()
    {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static Map<Item, Integer> required(Object... pairs)
    {
        Map<Item, Integer> map = new LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Item) pairs[i], (Integer) pairs[i + 1]);
        }

        return map;
    }

    private static Map<Item, Long> present(Object... pairs)
    {
        Map<Item, Long> map = new LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Item) pairs[i], (Long) pairs[i + 1]);
        }

        return map;
    }

    @Test
    @DisplayName("两个进度指标：完成种类/总种类 与 已收集数/所需数")
    void bothMetricsAreComputed()
    {
        MaterialProgress progress = MaterialProgress.join(
                required(Items.STONE, 100, Items.OAK_PLANKS, 50, Items.GLASS, 10, Items.BRICK, 4),
                present(Items.STONE, 100L, Items.OAK_PLANKS, 25L, Items.GLASS, 10L, Items.BRICK, 0L),
                MaterialProgress.Status.COMPLETE, 1000, 7L);

        assertEquals(4, progress.totalTypes());
        assertEquals(2, progress.completedTypes());
        assertEquals(0.5D, progress.typeProgress(), 1.0e-9);
        assertEquals(164L, progress.totalRequired());
        assertEquals(135L, progress.totalPresent());
        assertEquals(29L, progress.totalMissing());
        assertEquals(135.0D / 164.0D, progress.itemProgress(), 1.0e-9);
    }

    @Test
    @DisplayName("多囤的材料不会让数量进度超过 100%")
    void overCollectionIsCapped()
    {
        MaterialProgress progress = MaterialProgress.join(
                required(Items.STONE, 10),
                present(Items.STONE, 9999L),
                MaterialProgress.Status.COMPLETE, 1000, 1L);

        assertEquals(1.0D, progress.itemProgress(), 1.0e-9);
        assertEquals(1.0D, progress.typeProgress(), 1.0e-9);
        assertEquals(0L, progress.totalMissing());
        assertTrue(progress.rows().get(0).finished());
    }

    @Test
    @DisplayName("读不到原理图时必须标记为未知，而不是显示 0%")
    void unknownRequiredIsNotZeroPercent()
    {
        MaterialProgress progress = MaterialProgress.unknownRequired(present(Items.STONE, 64L), 1000, 3L);

        assertFalse(progress.hasRequired());
        assertEquals(MaterialProgress.Status.UNKNOWN_REQUIRED, progress.status());
        assertEquals(0, progress.totalTypes());
        assertEquals(64L, progress.totalPresent(), "已收集仍然有效");
    }

    @Test
    @DisplayName("额外囤积单独统计，不混进所需进度")
    void extraItemsAreReportedSeparately()
    {
        MaterialProgress progress = MaterialProgress.join(
                required(Items.STONE, 10),
                present(Items.STONE, 4L, Items.DIAMOND, 64L),
                MaterialProgress.Status.COMPLETE, 1000, 1L);

        assertEquals(64L, progress.totalExtra());
        assertEquals(1, progress.extraRows().size());
        assertEquals(Items.DIAMOND, progress.extraRows().get(0).item());
        assertEquals(0, progress.extraRows().get(0).required());
        assertEquals(1.0D, progress.extraRows().get(0).fraction(), 1.0e-9);
    }

    @Test
    @DisplayName("行按缺口降序排列，缺得最多的排最前")
    void rowsAreSortedByMissingDescending()
    {
        MaterialProgress progress = MaterialProgress.join(
                required(Items.STONE, 100, Items.GLASS, 10, Items.BRICK, 50),
                present(Items.STONE, 1L, Items.GLASS, 10L, Items.BRICK, 0L),
                MaterialProgress.Status.PARTIAL, 800, 2L);

        List<MaterialProgress.Row> rows = progress.rows();

        assertEquals(Items.STONE, rows.get(0).item());
        assertEquals(99L, rows.get(0).missing());
        assertEquals(Items.BRICK, rows.get(1).item());
        assertEquals(Items.GLASS, rows.get(2).item());
        assertTrue(rows.get(2).finished());
    }

    @Test
    @DisplayName("部分扫描状态会被保留，界面据此标注不完整")
    void partialStatusIsPreserved()
    {
        MaterialProgress progress = MaterialProgress.join(required(Items.STONE, 1), present(), MaterialProgress.Status.PARTIAL, 750, 5L);

        assertEquals(MaterialProgress.Status.PARTIAL, progress.status());
        assertEquals(750, progress.coveragePermille());
        assertEquals(5L, progress.revision());
    }
}

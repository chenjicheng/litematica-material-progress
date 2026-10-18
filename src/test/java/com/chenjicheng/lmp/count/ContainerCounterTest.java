package com.chenjicheng.lmp.count;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 计数语义回归测试。这些断言保护的是「材料进度数字必须正确」这条底线：
 * 数字算错比不显示更糟，所以每条语义都有独立用例。
 */
class ContainerCounterTest
{
    @BeforeAll
    static void bootstrapMinecraft()
    {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    void resetResolvers()
    {
        ContainerCounter.clearResolvers();
    }

    private static ItemStack shulkerWith(ItemStack... contents)
    {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(contents)));
        return box;
    }

    private static ItemStack emptyShulker()
    {
        return new ItemStack(Items.SHULKER_BOX);
    }

    private static long countOf(Map<Item, Long> counts, Item item)
    {
        return counts.getOrDefault(item, 0L);
    }

    @Test
    @DisplayName("空潜影盒按盒子本身计 1，而不是 0")
    void emptyShulkerCountsAsTheBoxItself()
    {
        SimpleInventory chest = new SimpleInventory(emptyShulker());
        Map<Item, Long> counts = ContainerCounter.countContainer(chest);

        assertEquals(1L, countOf(counts, Items.SHULKER_BOX));
    }

    @Test
    @DisplayName("有内容的潜影盒只计内容，不计盒子本身")
    void filledShulkerCountsContentsOnly()
    {
        SimpleInventory chest = new SimpleInventory(shulkerWith(new ItemStack(Items.STONE, 64)));
        Map<Item, Long> counts = ContainerCounter.countContainer(chest);

        assertEquals(64L, countOf(counts, Items.STONE));
        assertEquals(0L, countOf(counts, Items.SHULKER_BOX));
    }

    @Test
    @DisplayName("模组堆叠：一栈 64 个潜影盒，内容要乘 64")
    void stackedShulkersMultiplyTheirContents()
    {
        ItemStack stack = shulkerWith(new ItemStack(Items.DIAMOND, 3));
        stack.setCount(64);

        SimpleInventory chest = new SimpleInventory(stack);
        Map<Item, Long> counts = ContainerCounter.countContainer(chest);

        assertEquals(192L, countOf(counts, Items.DIAMOND), "3 颗 × 64 盒");
        assertEquals(0L, countOf(counts, Items.SHULKER_BOX));
    }

    @Test
    @DisplayName("模组堆叠：一栈 64 个空潜影盒，按 64 个盒子计")
    void stackedEmptyShulkersCountEachBox()
    {
        ItemStack stack = emptyShulker();
        stack.setCount(64);

        SimpleInventory chest = new SimpleInventory(stack);
        Map<Item, Long> counts = ContainerCounter.countContainer(chest);

        assertEquals(64L, countOf(counts, Items.SHULKER_BOX));
    }

    @Test
    @DisplayName("收纳袋：有内容计内容，空袋计袋子本身")
    void bundleSemantics()
    {
        ItemStack filled = new ItemStack(Items.BUNDLE);
        BundleContentsComponent.Builder builder = new BundleContentsComponent.Builder(BundleContentsComponent.DEFAULT);
        builder.add(new ItemStack(Items.EMERALD, 16));
        filled.set(DataComponentTypes.BUNDLE_CONTENTS, builder.build());

        SimpleInventory chest = new SimpleInventory(filled, new ItemStack(Items.BUNDLE));
        Map<Item, Long> counts = ContainerCounter.countContainer(chest);

        assertEquals(16L, countOf(counts, Items.EMERALD));
        assertEquals(1L, countOf(counts, Items.BUNDLE), "空收纳袋本身计 1");
    }

    @Test
    @DisplayName("普通物品按堆叠数累加")
    void plainItemsAccumulate()
    {
        SimpleInventory chest = new SimpleInventory(
                new ItemStack(Items.STONE, 64),
                new ItemStack(Items.STONE, 64),
                new ItemStack(Items.STONE, 32));

        Map<Item, Long> counts = ContainerCounter.countContainer(chest);

        assertEquals(160L, countOf(counts, Items.STONE));
    }

    @Test
    @DisplayName("深度保护：异常嵌套不会栈溢出，到达上限后按物品本身计")
    void depthGuardStopsRunawayNesting()
    {
        ItemStack inner = new ItemStack(Items.NETHERITE_INGOT, 1);

        // 构造远深于原版允许的嵌套（原版不允许潜影盒套潜影盒）
        for (int i = 0; i < ContainerCounter.DEFAULT_MAX_DEPTH + 4; i++)
        {
            inner = shulkerWith(inner);
        }

        SimpleInventory chest = new SimpleInventory(inner);
        Map<Item, Long> counts = ContainerCounter.countContainer(chest);

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total >= 1L, "深层嵌套仍应统计出至少一个物品，实际=" + counts);
    }

    @Test
    @DisplayName("扩展点：模组自定义堆叠布局优先于默认规则")
    void customResolverTakesPrecedence()
    {
        // 模拟某模组把 N 个盒子的内容分别存放：一栈 5 个盒子，每个盒子里 2 颗钻石
        ContainerCounter.registerResolver((stack, multiplier, out, depth) -> {
            if (stack.isOf(Items.SHULKER_BOX) && multiplier > 1L)
            {
                out.merge(Items.DIAMOND, 2L * multiplier, Long::sum);
                return true;
            }

            return false;
        });

        ItemStack stack = shulkerWith(new ItemStack(Items.DIAMOND, 999));
        stack.setCount(5);

        Map<Item, Long> counts = ContainerCounter.countContainer(new SimpleInventory(stack));

        assertEquals(10L, countOf(counts, Items.DIAMOND), "解析器给出的 2×5 应生效");
    }

    @Test
    @DisplayName("大箱子两半各 27 格：分别统计后相加，不重复也不遗漏")
    void doubleChestHalvesAreCountedSeparately()
    {
        SimpleInventory leftHalf = new SimpleInventory(27);
        SimpleInventory rightHalf = new SimpleInventory(27);
        leftHalf.setStack(0, new ItemStack(Items.OAK_PLANKS, 64));
        rightHalf.setStack(26, new ItemStack(Items.OAK_PLANKS, 64));

        Map<Item, Long> counts = new LinkedHashMap<>();
        ContainerCounter.countContainer(leftHalf, counts);
        ContainerCounter.countContainer(rightHalf, counts);

        assertEquals(128L, countOf(counts, Items.OAK_PLANKS));
    }

    @Test
    @DisplayName("空容器不产生任何条目")
    void emptyContainerYieldsNoEntries()
    {
        Map<Item, Long> counts = ContainerCounter.countContainer(new SimpleInventory(27));

        assertTrue(counts.isEmpty());
        assertNull(counts.get(Items.STONE));
    }
}

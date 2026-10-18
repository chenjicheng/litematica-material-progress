package com.chenjicheng.lmp.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RequiredMaterialCalculator} 的回归测试：逐条锁定 Litematica 材料清单的换算语义。
 *
 * <p>重点覆盖最容易算错、且直接影响「进度数字」的几条：含水方块额外计水桶、双台阶=2、雪层=层数、
 * 门/床/高植株只计下半、无物品方块丢弃，以及用户特别关心的<b>潜影盒</b>——所需侧每个潜影盒方块只计 1 个
 * 潜影盒物品，且<b>不</b>读 TileEntities 里的内容物（那是「已收集」侧才做的事，见 ContainerCounter）。
 */
class RequiredMaterialCalculatorTest
{
    @BeforeAll
    static void bootstrapMinecraft()
    {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static int count(Map<Item, Integer> required, Item item)
    {
        return required.getOrDefault(item, 0);
    }

    private static Map<Item, Integer> requiredFrom(Path file)
    {
        final Optional<LitematicReader.Litematic> parsed = LitematicReader.read(file);
        assertTrue(parsed.isPresent(), "fixture 应能解析");
        return RequiredMaterialCalculator.calculate(parsed.get());
    }

    private static Map<Item, Integer> required(Path dir, List<NbtCompound> palette, int[] ids,
                                               int sizeX, int sizeY, int sizeZ) throws IOException
    {
        return requiredFrom(LitematicReaderTest.writeSingleRegion(
                dir, LitematicReader.currentDataVersion(), sizeX, sizeY, sizeZ, palette, ids));
    }

    @Test
    @DisplayName("简单调色板：按方块状态累加，空气被丢弃")
    void simplePaletteCounts(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:stone"),
                LitematicReaderTest.state("minecraft:oak_planks"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2, 1, 0}, 2, 1, 2);

        assertEquals(2, count(required, Items.STONE));
        assertEquals(1, count(required, Items.OAK_PLANKS));
        assertFalse(required.containsKey(Items.AIR), "空气不得出现在所需清单里");
    }

    @Test
    @DisplayName("双台阶需要 2 个台阶物品")
    void doubleSlabRequiresTwo(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:stone_slab", "type", "double"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(2, count(required, Items.STONE_SLAB));
    }

    @Test
    @DisplayName("含水方块：基础物品 + 额外 1 个水桶")
    void waterloggedAddsWaterBucket(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:oak_slab", "type", "bottom", "waterlogged", "true"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(1, count(required, Items.OAK_SLAB));
        assertEquals(1, count(required, Items.WATER_BUCKET));
    }

    @Test
    @DisplayName("门只计下半，上半不计（否则每扇门翻倍）")
    void doorUpperHalfIsDropped(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:oak_door", "half", "lower"),
                LitematicReaderTest.state("minecraft:oak_door", "half", "upper"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2}, 1, 2, 1);

        assertEquals(1, count(required, Items.OAK_DOOR));
    }

    @Test
    @DisplayName("雪层按 LAYERS 数量计")
    void snowLayersCount(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:snow", "layers", "3"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(3, count(required, Items.SNOW));
    }

    @Test
    @DisplayName("水源计水桶，流动水不计")
    void fluidSourceVsFlowing(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:water", "level", "0"),
                LitematicReaderTest.state("minecraft:water", "level", "3"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2}, 2, 1, 1);

        assertEquals(1, count(required, Items.WATER_BUCKET), "只有水源（level=0）计 1 桶");
    }

    @Test
    @DisplayName("耕地按泥土计")
    void farmlandCountsAsDirt(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:farmland", "moisture", "0"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(1, count(required, Items.DIRT));
    }

    @Test
    @DisplayName("无物品形态的方块（火）被丢弃，不产生 0 数量条目")
    void noItemBlockIsDropped(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:fire"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertTrue(required.isEmpty(), "火没有物品形态，所需清单应为空而不是含 0 条目");
    }

    @Test
    @DisplayName("潜影盒：所需侧每个方块只计 1 个盒子，且不读 TileEntities 里的内容物")
    void shulkerBoxRequiredIgnoresContents(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:shulker_box"));

        // 一个潜影盒方块，方块实体里塞 64 颗钻石——这些内容物不是所需材料
        final NbtCompound region = LitematicReaderTest.region(1, 1, 1, palette, new int[]{1});
        final NbtCompound tile = new NbtCompound();
        tile.putInt("x", 0);
        tile.putInt("y", 0);
        tile.putInt("z", 0);
        tile.putString("id", "minecraft:shulker_box");

        final NbtCompound stored = new NbtCompound();
        stored.putString("id", "minecraft:diamond");
        stored.putInt("count", 64);

        final NbtList storedItems = new NbtList();
        storedItems.add(stored);
        tile.put("Items", storedItems);

        final NbtList tiles = new NbtList();
        tiles.add(tile);
        region.put("TileEntities", tiles);

        final Map<String, NbtCompound> regions = new LinkedHashMap<>();
        regions.put("r", region);

        final Path file = LitematicReaderTest.write(dir, "shulker.litematic",
                LitematicReaderTest.schematic(LitematicReader.currentDataVersion(), regions));

        final Map<Item, Integer> required = requiredFrom(file);

        assertEquals(1, count(required, Items.SHULKER_BOX), "每个潜影盒方块需要 1 个潜影盒物品");
        assertFalse(required.containsKey(Items.DIAMOND), "潜影盒内容物不得计入所需材料");
    }

    @Test
    @DisplayName("潜影盒：N 个方块需要 N 个盒子，与是否可堆叠无关")
    void shulkerBoxCountScalesWithBlocks(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:shulker_box"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 1, 1, 1, 1}, 5, 1, 1);

        assertEquals(5, count(required, Items.SHULKER_BOX));
    }
    // ------------------------------------------------------------------
    // 特殊方块表：每条分支都直接影响「还差多少」，必须逐条锁住
    // ------------------------------------------------------------------

    @Test
    @DisplayName("装植物的花盆 = 花盆 + 植物本身")
    void flowerPotWithContent(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:potted_dandelion"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(1, count(required, Items.FLOWER_POT));
        assertEquals(1, count(required, Items.DANDELION));
    }

    @Test
    @DisplayName("空花盆只计花盆")
    void emptyFlowerPot(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:flower_pot"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(1, count(required, Items.FLOWER_POT));
    }

    @Test
    @DisplayName("空炼药锅只计炼药锅")
    void emptyCauldron(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:cauldron"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(1, count(required, Items.CAULDRON));
    }

    @Test
    @DisplayName("水炼药锅：1/2 级各计 1/2 个水瓶，3 级计 1 个水桶，且都额外计 1 个炼药锅")
    void waterCauldronLevels(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:water_cauldron", "level", "1"),
                LitematicReaderTest.state("minecraft:water_cauldron", "level", "2"),
                LitematicReaderTest.state("minecraft:water_cauldron", "level", "3"));

        // 每个状态各 1 个方块
        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2, 3}, 3, 1, 1);

        assertEquals(3, count(required, Items.CAULDRON), "三种等级各需要 1 个炼药锅");
        assertEquals(3, count(required, Items.POTION), "1 级 1 瓶 + 2 级 2 瓶");
        assertEquals(1, count(required, Items.WATER_BUCKET), "3 级按整桶水算");
    }

    @Test
    @DisplayName("岩浆炼药锅与细雪炼药锅：炼药锅 + 对应的桶")
    void lavaAndPowderSnowCauldrons(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:lava_cauldron"),
                LitematicReaderTest.state("minecraft:powder_snow_cauldron", "level", "3"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2}, 2, 1, 1);

        assertEquals(2, count(required, Items.CAULDRON));
        assertEquals(1, count(required, Items.LAVA_BUCKET));
        assertEquals(1, count(required, Items.POWDER_SNOW_BUCKET));
    }

    @Test
    @DisplayName("多面方块按可用面数计（发光地衣贴两面 = 2 个）")
    void multifaceCountsFaces(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:glow_lichen", "north", "true", "up", "true"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1}, 1, 1, 1);

        assertEquals(2, count(required, Items.GLOW_LICHEN));
    }

    @Test
    @DisplayName("海龟蛋 / 海泡菜 / 蜡烛按数量属性计")
    void countableBlocksUseTheirProperty(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:turtle_egg", "eggs", "3"),
                LitematicReaderTest.state("minecraft:candle", "candles", "4"),
                LitematicReaderTest.state("minecraft:sea_pickle", "pickles", "2", "waterlogged", "false"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2, 3}, 3, 1, 1);

        assertEquals(3, count(required, Items.TURTLE_EGG));
        assertEquals(4, count(required, Items.CANDLE));
        assertEquals(2, count(required, Items.SEA_PICKLE));
    }

    @Test
    @DisplayName("岩浆源计岩浆桶，流动岩浆不计")
    void lavaSourceVersusFlowing(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:lava", "level", "0"),
                LitematicReaderTest.state("minecraft:lava", "level", "3"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2}, 2, 1, 1);

        assertEquals(1, count(required, Items.LAVA_BUCKET));
    }

    @Test
    @DisplayName("床只计床脚，床头不计（否则每张床翻倍）")
    void bedCountsFootOnly(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:red_bed", "part", "foot", "facing", "north"),
                LitematicReaderTest.state("minecraft:red_bed", "part", "head", "facing", "north"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2}, 2, 1, 1);

        assertEquals(1, count(required, Items.RED_BED));
    }

    @Test
    @DisplayName("高植株只计下半，上半不计")
    void tallPlantCountsLowerHalfOnly(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:tall_grass", "half", "lower"),
                LitematicReaderTest.state("minecraft:tall_grass", "half", "upper"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2}, 2, 1, 1);

        assertEquals(1, count(required, Items.TALL_GRASS));
    }

    @Test
    @DisplayName("无对应物品的方块状态（活塞头、传送门）被丢弃")
    void pistonHeadAndPortalsAreDropped(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                LitematicReaderTest.state("minecraft:air"),
                LitematicReaderTest.state("minecraft:piston_head", "facing", "north"),
                LitematicReaderTest.state("minecraft:nether_portal", "axis", "x"));

        final Map<Item, Integer> required = required(dir, palette, new int[]{1, 2}, 2, 1, 1);

        assertTrue(required.isEmpty(), "活塞头与传送门不应产生任何所需物品，实际：" + required);
    }

    @Test
    @DisplayName("累加饱和到 Integer.MAX_VALUE，不因异常大的区域溢出成负数")
    void accumulationSaturates()
    {
        final Map<Item, Integer> required = new LinkedHashMap<>();

        RequiredMaterialCalculator.addState(required, Blocks.STONE.getDefaultState(), 3_000_000_000L);
        RequiredMaterialCalculator.addState(required, Blocks.STONE.getDefaultState(), 3_000_000_000L);

        assertEquals(Integer.MAX_VALUE, count(required, Items.STONE));
    }
}

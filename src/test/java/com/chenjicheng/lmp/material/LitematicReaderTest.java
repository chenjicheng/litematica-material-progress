package com.chenjicheng.lmp.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LitematicReader} 的回归测试。
 *
 * <p>测试里的 {@code .litematic} 全部<b>在代码里手写 NBT</b> 再 {@link NbtIo#writeCompressed} 落盘，
 * 以此证明读取端确实理解真实布局：位宽推导、低位在前可跨 long 的解包、索引顺序（x 最快）、
 * 以及各失败路径（截断、损坏、高于当前的版本、无法解析的方块名）都必须返回 {@link Optional#empty()}。
 *
 * <p>本类同时提供一组包内共享的 fixture 构造方法，供 {@link RequiredMaterialCalculatorTest} 复用，
 * 避免在两个测试里重复造同一份 NBT 布局。
 */
class LitematicReaderTest
{
    @BeforeAll
    static void bootstrapMinecraft()
    {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    // ------------------------------------------------------------------
    // 共享 fixture 构造（包内可见，供计算器测试复用）
    // ------------------------------------------------------------------

    /** 构造一个调色板条目：{@code {Name:..., Properties:{...}}}；props 为 key/value 交替的变长参数。 */
    static NbtCompound state(String name, String... props)
    {
        final NbtCompound tag = new NbtCompound();
        tag.putString("Name", name);

        if (props.length >= 2)
        {
            final NbtCompound properties = new NbtCompound();

            for (int i = 0; i + 1 < props.length; i += 2)
            {
                properties.putString(props[i], props[i + 1]);
            }

            tag.put("Properties", properties);
        }

        return tag;
    }

    /** 构造一个 {@code {x,y,z}} 的 int 三元组。 */
    static NbtCompound vec3i(int x, int y, int z)
    {
        final NbtCompound tag = new NbtCompound();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    /** 按 LitematicaBitArray 的布局把调色板 id 打包成 long 数组（低位在前、可跨 long）。 */
    static long[] pack(int bits, int[] ids)
    {
        final long[] out = new long[(int) ((((long) ids.length * bits) + 63L) / 64L)];
        final long maxEntry = (1L << bits) - 1L;

        for (int i = 0; i < ids.length; i++)
        {
            final long value = ids[i] & maxEntry;
            final long startOffset = (long) i * (long) bits;
            final int startArr = (int) (startOffset >> 6);
            final int startBit = (int) (startOffset & 0x3FL);

            out[startArr] |= value << startBit;

            if (startBit + bits > 64)
            {
                out[startArr + 1] |= value >>> (64 - startBit);
            }
        }

        return out;
    }

    /** 构造一个区域：按调色板长度自动推导位宽并打包 ids（ids 长度必须等于 sx*sy*sz）。 */
    static NbtCompound region(int sizeX, int sizeY, int sizeZ, List<NbtCompound> palette, int[] ids)
    {
        final NbtList paletteList = new NbtList();
        palette.forEach(paletteList::add);

        final NbtCompound region = new NbtCompound();
        region.put("BlockStatePalette", paletteList);

        final int bits = LitematicReader.bitsForPalette(palette.size());
        region.put("BlockStates", new NbtLongArray(pack(bits, ids)));
        region.put("Position", vec3i(0, 0, 0));
        region.put("Size", vec3i(sizeX, sizeY, sizeZ));

        return region;
    }

    /** 构造一张完整原理图（版本 7，SubVersion 1）。 */
    static NbtCompound schematic(int dataVersion, Map<String, NbtCompound> regions)
    {
        final NbtCompound regionsTag = new NbtCompound();
        regions.forEach(regionsTag::put);

        final NbtCompound root = new NbtCompound();
        root.putInt("MinecraftDataVersion", dataVersion);
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.put("Metadata", new NbtCompound());
        root.put("Regions", regionsTag);

        return root;
    }

    /** 把原理图写成 gzip 压缩的 .litematic 文件。 */
    static Path write(Path dir, String fileName, NbtCompound root) throws IOException
    {
        final Path file = dir.resolve(fileName);
        NbtIo.writeCompressed(root, file);
        return file;
    }

    /** 单区域原理图的便捷构造。 */
    static Path writeSingleRegion(Path dir, int dataVersion,
                                  int sizeX, int sizeY, int sizeZ,
                                  List<NbtCompound> palette, int[] ids) throws IOException
    {
        final Map<String, NbtCompound> regions = new LinkedHashMap<>();
        regions.put("main", region(sizeX, sizeY, sizeZ, palette, ids));
        return write(dir, "single.litematic", schematic(dataVersion, regions));
    }

    /** 按名字查区域（NbtCompound 不保证键顺序，测试不应依赖下标）。 */
    static LitematicReader.Region regionByName(LitematicReader.Litematic schematic, String name)
    {
        for (LitematicReader.Region region : schematic.regions())
        {
            if (region.name().equals(name))
            {
                return region;
            }
        }

        throw new AssertionError("找不到区域：" + name);
    }

    // ------------------------------------------------------------------
    // 位宽推导
    // ------------------------------------------------------------------

    @Test
    @DisplayName("位宽由调色板长度推导：下限 2 位，向上取整到 log2")
    void bitsForPaletteMath()
    {
        assertEquals(2, LitematicReader.bitsForPalette(1));
        assertEquals(2, LitematicReader.bitsForPalette(4));
        assertEquals(3, LitematicReader.bitsForPalette(5));
        assertEquals(3, LitematicReader.bitsForPalette(8));
        assertEquals(4, LitematicReader.bitsForPalette(9));
    }

    // ------------------------------------------------------------------
    // 基本读取与索引顺序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("简单调色板：索引顺序为 x 最快，位置映射正确")
    void readsSimplePaletteAndMapsPositions(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                state("minecraft:air"),
                state("minecraft:stone"),
                state("minecraft:oak_planks"));

        // 2x1x2，index = y*(sx*sz) + z*sx + x
        final int[] ids = {1, 2, 1, 0};
        final Path file = writeSingleRegion(dir, LitematicReader.currentDataVersion(), 2, 1, 2, palette, ids);

        final Optional<LitematicReader.Litematic> parsed = LitematicReader.read(file);
        assertTrue(parsed.isPresent(), "合法文件应能解析");

        final LitematicReader.Litematic schematic = parsed.get();
        assertEquals(1, schematic.regions().size());

        final LitematicReader.Region region = schematic.regions().get(0);
        assertEquals(2, region.sizeX());
        assertEquals(1, region.sizeY());
        assertEquals(2, region.sizeZ());
        assertEquals(2, region.bitsPerEntry());
        assertEquals(4L, region.volume());

        assertEquals(Blocks.STONE.getDefaultState(), region.stateAt(0, 0, 0));
        assertEquals(Blocks.OAK_PLANKS.getDefaultState(), region.stateAt(1, 0, 0));
        assertEquals(Blocks.STONE.getDefaultState(), region.stateAt(0, 0, 1));
        assertEquals(Blocks.AIR.getDefaultState(), region.stateAt(1, 0, 1));
    }

    @Test
    @DisplayName("多区域：逐个读取，尺寸各自正确")
    void readsMultipleRegions(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));

        final Map<String, NbtCompound> regions = new LinkedHashMap<>();
        regions.put("west", region(3, 1, 1, palette, new int[]{1, 1, 1}));
        regions.put("east", region(1, 2, 2, palette, new int[]{1, 0, 1, 1}));

        final Path file = write(dir, "multi.litematic", schematic(LitematicReader.currentDataVersion(), regions));
        final Optional<LitematicReader.Litematic> parsed = LitematicReader.read(file);

        assertTrue(parsed.isPresent());
        assertEquals(2, parsed.get().regions().size());

        // NbtCompound 不保证键顺序，因此按名字查区域，不依赖下标
        final LitematicReader.Region west = regionByName(parsed.get(), "west");
        final LitematicReader.Region east = regionByName(parsed.get(), "east");

        assertEquals(3, west.sizeX());
        assertEquals(1, west.sizeY());
        assertEquals(1, west.sizeZ());
        assertEquals(1, east.sizeX());
        assertEquals(2, east.sizeY());
        assertEquals(2, east.sizeZ());
    }

    @Test
    @DisplayName("打包/解包数学：bits=3 且条目跨 long 边界时仍取到正确方块")
    void unpacksEntriesCrossingLongBoundary(@TempDir Path dir) throws IOException
    {
        // 5 个调色板条目 → bits=3；64/3=21.33，故 index 21 起跨 long
        final List<NbtCompound> palette = List.of(
                state("minecraft:air"),
                state("minecraft:stone"),
                state("minecraft:dirt"),
                state("minecraft:oak_planks"),
                state("minecraft:glass"));

        // 3x1x8 = 24 个方块；除 index 21=dirt、index 23=glass 外全是石头
        final int[] ids = new int[24];
        java.util.Arrays.fill(ids, 1);
        ids[21] = 2;
        ids[23] = 4;

        final Path file = writeSingleRegion(dir, LitematicReader.currentDataVersion(), 3, 1, 8, palette, ids);
        final Optional<LitematicReader.Litematic> parsed = LitematicReader.read(file);

        assertTrue(parsed.isPresent());
        final LitematicReader.Region region = parsed.get().regions().get(0);
        assertEquals(3, region.bitsPerEntry());

        // index 21 → y=0,z=7,x=0；index 23 → y=0,z=7,x=2
        assertEquals(Blocks.DIRT.getDefaultState(), region.stateAt(0, 0, 7));
        assertEquals(Blocks.GLASS.getDefaultState(), region.stateAt(2, 0, 7));
        assertEquals(Blocks.STONE.getDefaultState(), region.stateAt(1, 0, 7));
        assertEquals(Blocks.STONE.getDefaultState(), region.stateAt(2, 0, 6));
    }

    @Test
    @DisplayName("负 Size（反向框选）也要归一化成正确容器尺寸")
    void normalizesNegativeSize(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        final int[] ids = new int[12]; // 3*1*4
        java.util.Arrays.fill(ids, 1);

        final NbtCompound region = region(1, 1, 1, palette, new int[]{1});
        region.put("Position", vec3i(10, 0, 10));
        region.put("Size", vec3i(-3, 1, -4)); // 各轴取绝对值后应为 3x1x4

        final Map<String, NbtCompound> regions = new LinkedHashMap<>();
        regions.put("neg", region);

        final Path file = write(dir, "neg.litematic", schematic(LitematicReader.currentDataVersion(), regions));
        final Optional<LitematicReader.Litematic> parsed = LitematicReader.read(file);

        assertTrue(parsed.isPresent());
        final LitematicReader.Region r = parsed.get().regions().get(0);
        assertEquals(3, r.sizeX());
        assertEquals(1, r.sizeY());
        assertEquals(4, r.sizeZ());
        assertEquals(10, r.positionX());
        assertEquals(10, r.positionZ());
    }

    // ------------------------------------------------------------------
    // 失败路径：一律 Optional.empty()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("文件不存在 → 未知（empty），不是异常")
    void missingFileYieldsEmpty(@TempDir Path dir)
    {
        assertTrue(LitematicReader.read(dir.resolve("nope.litematic")).isEmpty());
    }

    @Test
    @DisplayName("损坏（非 NBT/gzip）文件 → 未知（empty）")
    void corruptFileYieldsEmpty(@TempDir Path dir) throws IOException
    {
        final Path file = dir.resolve("broken.litematic");
        Files.write(file, new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55});

        assertTrue(LitematicReader.read(file).isEmpty());
    }

    @Test
    @DisplayName("BlockStates 被截断 → 未知（empty），绝不返回半成品")
    void truncatedBlockStatesYieldsEmpty(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        final int[] ids = new int[64]; // 8x1x8，bits=2 → 需要 2 个 long

        final NbtCompound region = region(8, 1, 8, palette, ids);
        region.put("BlockStates", new NbtLongArray(new long[]{0L})); // 只留 1 个 long

        final Map<String, NbtCompound> regions = new LinkedHashMap<>();
        regions.put("bad", region);

        final Path file = write(dir, "truncated.litematic", schematic(LitematicReader.currentDataVersion(), regions));
        assertTrue(LitematicReader.read(file).isEmpty());
    }

    @Test
    @DisplayName("老 MinecraftDataVersion 但调色板可解析 → 接受并记录版本（不因版本号一律拒绝）")
    void olderDataVersionWithResolvablePaletteIsAccepted(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        // 3465 明显低于 1.21.11 的 4671；拒绝与否取决于调色板能否解析，而不是版本号
        final Path file = writeSingleRegion(dir, 3465, 1, 1, 1, palette, new int[]{1});

        final Optional<LitematicReader.Litematic> parsed = LitematicReader.read(file);

        assertTrue(parsed.isPresent());
        assertEquals(3465, parsed.get().minecraftDataVersion());
        assertEquals(Blocks.STONE.getDefaultState(), parsed.get().regions().get(0).stateAt(0, 0, 0));
    }

    @Test
    @DisplayName("老 MinecraftDataVersion 且方块名已不存在 → 拒绝（empty），不静默算少")
    void olderDataVersionWithStaleBlockNameYieldsEmpty(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                state("minecraft:air"),
                state("minecraft:this_block_was_removed"));

        final Path file = writeSingleRegion(dir, 3465, 1, 1, 1, palette, new int[]{1});
        assertTrue(LitematicReader.read(file).isEmpty());
    }

    @Test
    @DisplayName("MinecraftDataVersion 高于当前运行版本 → 拒绝（empty）")
    void newerDataVersionYieldsEmpty(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        final Path file = writeSingleRegion(dir, LitematicReader.currentDataVersion() + 1, 1, 1, 1, palette, new int[]{1});

        assertTrue(LitematicReader.read(file).isEmpty());
    }

    @Test
    @DisplayName("调色板里的方块名无法解析 → 拒绝（empty），避免静默变 AIR")
    void unresolvableBlockNameYieldsEmpty(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(
                state("minecraft:air"),
                state("minecraft:definitely_not_a_real_block"));

        final Path file = writeSingleRegion(dir, LitematicReader.currentDataVersion(), 1, 1, 1, palette, new int[]{1});
        assertTrue(LitematicReader.read(file).isEmpty());
    }

    @Test
    @DisplayName("非法格式版本 → 拒绝（empty）")
    void unsupportedFormatVersionYieldsEmpty(@TempDir Path dir) throws IOException
    {
        final List<NbtCompound> palette = List.of(state("minecraft:air"), state("minecraft:stone"));
        final Map<String, NbtCompound> regions = new LinkedHashMap<>();
        regions.put("main", region(1, 1, 1, palette, new int[]{1}));

        final NbtCompound root = schematic(LitematicReader.currentDataVersion(), regions);
        root.putInt("Version", 99); // 超出支持范围

        final Path file = write(dir, "badversion.litematic", root);
        assertTrue(LitematicReader.read(file).isEmpty());
    }

    @Test
    @DisplayName("小写 name/properties 键也能解析（兼容非 Litematica 变体）")
    void lowercaseKeysAreAccepted(@TempDir Path dir) throws IOException
    {
        final NbtCompound lower = new NbtCompound();
        lower.putString("name", "minecraft:oak_slab");

        final NbtCompound props = new NbtCompound();
        props.putString("type", "double");
        lower.put("properties", props);

        final List<NbtCompound> palette = List.of(state("minecraft:air"), lower);
        final Path file = writeSingleRegion(dir, LitematicReader.currentDataVersion(), 1, 1, 1, palette, new int[]{1});

        final Optional<LitematicReader.Litematic> parsed = LitematicReader.read(file);
        assertTrue(parsed.isPresent());
        assertFalse(parsed.get().regions().get(0).stateAt(0, 0, 0).isAir());
    }
}

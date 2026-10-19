package com.chenjicheng.lmp.material;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.chenjicheng.lmp.LmpMod;

import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.State;

/**
 * 只读的 {@code .litematic} 解析器：把 Litematica 写出的 gzip 压缩 NBT 还原成「按区域的方块状态容器」，
 * 供服务端统计所需材料。
 *
 * <p>文件格式（已按 Litematica/Servux 上游实现核对）：
 * <ul>
 *   <li>根是一个未命名 Compound，顶层键 {@code Version}(int)、{@code MinecraftDataVersion}(int)、
 *       {@code Metadata}(Compound)、{@code Regions}(Compound)。</li>
 *   <li>每个区域 {@code Regions.<名字>} 含 {@code BlockStatePalette}(List&lt;Compound&gt;)、
 *       {@code BlockStates}(LongArray)、{@code Position}/{@code Size}(各含 x/y/z int)。</li>
 *   <li>每个调色板条目就是一个原版方块状态 NBT：{@code {Name:"minecraft:stone", Properties:{...}}}。</li>
 *   <li>位宽不写进文件，必须由调色板长度推导：{@code bits = max(2, 32 - numberOfLeadingZeros(n-1))}；
 *       解包是 Litematica 自有的低位在前、可跨 long 的紧凑方案，<b>不是</b>原版 PalettedContainer 的
 *       4 位对齐方案。</li>
 *   <li>索引 {@code = y*(sizeX*sizeZ) + z*sizeX + x}（x 最快）。</li>
 *   <li>{@code Position} 是「区域 pos1 − 原理图原点」的相对偏移，不是世界坐标；{@code Size} 各轴可能为负
 *       （玩家反向框选），因此容器尺寸按 min/max 归一化后得出。</li>
 * </ul>
 *
 * <p><b>数据版本策略（硬约束）</b>：本项目不做 DataFixer 转换，判据是<b>实际能不能解析</b>而不是版本号。
 * 真正的危险是「静默算错」：旧方块名在当前注册表里不存在时，{@link NbtHelper#toBlockState} 会把该条目
 * 回落成 AIR，所需清单悄悄算少、进度虚高，比不显示更糟。因此本类的拒绝条件是
 * 「调色板里出现<b>有名字却解析成 AIR</b>的条目」，它与版本号无关，能直接命中这个失败模式。
 *
 * <p>版本号只用于两件事：{@code MinecraftDataVersion} <b>高于</b>当前运行版本（来自更新的游戏，
 * 格式未知）直接拒绝；<b>低于</b>当前运行版本只记一条告警后继续解析——否则服务器每升级一次游戏版本，
 * 所有已共享的原理图都会永久变成「所需未知」，而其中绝大多数其实完全可解析。
 *
 * <p>本类只做纯 NBT 解析与注册表查表，<b>不访问世界</b>，因此可在服务端 tick 线程之外安全调用
 * （由调度器在工作线程或有界步进里执行）。
 */
public final class LitematicReader
{
    /** Litematica 当前原理图格式版本上限；超过它说明文件来自更新的 Litematica，拒绝。 */
    public static final int MAX_SCHEMATIC_VERSION = 7;

    /** 原理图格式版本下限（版本 1 起才有可用的区域数据）。 */
    public static final int MIN_SCHEMATIC_VERSION = 1;

    /** 文件缺少 MinecraftDataVersion 时的回落值（对应 1.12，上游 Servux 同此默认）。 */
    public static final int FALLBACK_DATA_VERSION_1_12 = 1139;

    private static final String KEY_VERSION = "Version";
    private static final String KEY_DATA_VERSION = "MinecraftDataVersion";
    private static final String KEY_REGIONS = "Regions";
    private static final String KEY_PALETTE = "BlockStatePalette";
    private static final String KEY_BLOCK_STATES = "BlockStates";
    private static final String KEY_POSITION = "Position";
    private static final String KEY_SIZE = "Size";
    private static final String KEY_LOWER_NAME = "name";
    private static final String KEY_LOWER_PROPERTIES = "properties";

    private LitematicReader()
    {
    }

    /**
     * 解析一个 {@code .litematic} 文件。
     *
     * <p>任何失败（文件不存在、gzip/NBT 损坏、数组被截断、格式版本或数据版本不受支持、方块名无法解析）
     * 都返回 {@link Optional#empty()}，绝不返回一个「空区域」的半成品——那会让调用方把所需材料当成 0。
     *
     * @param file 原理图文件路径（可为 null，此时直接返回空）
     * @return 解析结果；{@link Optional#empty()} 表示未知/不可用
     */
    public static Optional<Litematic> read(Path file)
    {
        if (file == null)
        {
            return Optional.empty();
        }

        final NbtCompound root;

        try
        {
            root = readCompressedOrPlain(file);
        }
        catch (IOException | RuntimeException e)
        {
            LmpMod.LOGGER.warn("无法读取原理图 {}：{}", file, e.toString());
            return Optional.empty();
        }

        if (root == null)
        {
            LmpMod.LOGGER.warn("原理图文件不存在或为空：{}", file);
            return Optional.empty();
        }

        final int formatVersion = root.getInt(KEY_VERSION, -1);

        if (formatVersion < MIN_SCHEMATIC_VERSION || formatVersion > MAX_SCHEMATIC_VERSION)
        {
            LmpMod.LOGGER.warn("原理图 {} 的格式版本 {} 不受支持（支持 {}..{}）",
                    file, formatVersion, MIN_SCHEMATIC_VERSION, MAX_SCHEMATIC_VERSION);
            return Optional.empty();
        }

        final int dataVersion = root.getInt(KEY_DATA_VERSION, FALLBACK_DATA_VERSION_1_12);

        if (dataVersion > currentDataVersion())
        {
            LmpMod.LOGGER.warn("原理图 {} 的 MinecraftDataVersion={} 高于当前 {}：文件来自更新的游戏版本，拒绝解析",
                    file, dataVersion, currentDataVersion());
            return Optional.empty();
        }

        if (dataVersion < currentDataVersion())
        {
            // 不拒绝：判据是下面逐个调色板条目的解析结果，而不是版本号（见类注释）
            LmpMod.LOGGER.warn("原理图 {} 的 MinecraftDataVersion={} 低于当前 {}：不做数据版本转换，"
                            + "若有方块名已改名会在调色板解析时拒绝", file, dataVersion, currentDataVersion());
        }

        final NbtCompound regionsTag = root.getCompound(KEY_REGIONS).orElse(null);

        if (regionsTag == null || regionsTag.getKeys().isEmpty())
        {
            LmpMod.LOGGER.warn("原理图 {} 缺少 Regions 或 Regions 为空", file);
            return Optional.empty();
        }

        final List<Region> regions = new ArrayList<>(regionsTag.getKeys().size());

        for (String regionName : regionsTag.getKeys())
        {
            final NbtCompound regionTag = regionsTag.getCompound(regionName).orElse(null);

            if (regionTag == null)
            {
                LmpMod.LOGGER.warn("原理图 {} 的区域 {} 不是 Compound", file, regionName);
                return Optional.empty();
            }

            final Optional<Region> region = readRegion(file, regionName, regionTag);

            if (region.isEmpty())
            {
                return Optional.empty();
            }

            regions.add(region.get());
        }

        return Optional.of(new Litematic(formatVersion, dataVersion, regions));
    }

    /** 当前运行版本的数据版本号（1.21.11 为 4671），用于判定原理图是否受支持。 */
    public static int currentDataVersion()
    {
        return SharedConstants.getGameVersion().dataVersion().id();
    }

    /**
     * 该数据版本是否受支持。
     *
     * <p>只拒绝<b>高于</b>当前运行版本的文件（格式未知）；更旧的文件交给调色板解析去判定，
     * 见类注释里的数据版本策略。
     */
    public static boolean isSupportedDataVersion(int dataVersion)
    {
        return dataVersion <= currentDataVersion();
    }

    /** 由调色板长度推导每个条目占用的位数：{@code max(2, ceil(log2(n)))}，下限 2 位。 */
    public static int bitsForPalette(int paletteSize)
    {
        if (paletteSize <= 1)
        {
            return 2;
        }

        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    private static Optional<Region> readRegion(Path file, String regionName, NbtCompound regionTag)
    {
        final NbtCompound posTag = regionTag.getCompound(KEY_POSITION).orElse(null);
        final NbtCompound sizeTag = regionTag.getCompound(KEY_SIZE).orElse(null);

        if (posTag == null || sizeTag == null)
        {
            LmpMod.LOGGER.warn("原理图 {} 的区域 {} 缺少 Position/Size", file, regionName);
            return Optional.empty();
        }

        final int positionX = posTag.getInt("x", 0);
        final int positionY = posTag.getInt("y", 0);
        final int positionZ = posTag.getInt("z", 0);
        final int sizeX = normalizeSize(positionX, sizeTag.getInt("x", 0));
        final int sizeY = normalizeSize(positionY, sizeTag.getInt("y", 0));
        final int sizeZ = normalizeSize(positionZ, sizeTag.getInt("z", 0));

        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
        {
            LmpMod.LOGGER.warn("原理图 {} 的区域 {} 尺寸非法（{}x{}x{}）",
                    file, regionName, sizeX, sizeY, sizeZ);
            return Optional.empty();
        }

        final NbtList paletteList = regionTag.getList(KEY_PALETTE).orElse(null);

        if (paletteList == null || paletteList.isEmpty())
        {
            LmpMod.LOGGER.warn("原理图 {} 的区域 {} 缺少 BlockStatePalette", file, regionName);
            return Optional.empty();
        }

        final int paletteSize = paletteList.size();
        final BlockState[] palette = new BlockState[paletteSize];

        for (int i = 0; i < paletteSize; i++)
        {
            final NbtCompound entry = paletteList.getCompoundOrEmpty(i);
            final BlockState state = decodePaletteEntry(entry);

            if (state == null)
            {
                LmpMod.LOGGER.warn("原理图 {} 的区域 {} 调色板第 {} 项无法解析（方块名在当前注册表中不存在），"
                                + "拒绝解析以免静默算错", file, regionName, i);
                return Optional.empty();
            }

            palette[i] = state;
        }

        final long[] blockStates = regionTag.getLongArray(KEY_BLOCK_STATES).orElse(null);

        if (blockStates == null)
        {
            LmpMod.LOGGER.warn("原理图 {} 的区域 {} 缺少 BlockStates", file, regionName);
            return Optional.empty();
        }

        final int bitsPerEntry = bitsForPalette(paletteSize);
        final long volume;
        final long requiredLongs;

        try
        {
            // 逐级 multiplyExact：三个 int 直接相乘会静默溢出 long（每个都可能是 2^31 量级），
            // 溢出后体积变成一个小数，下面「BlockStates 被截断」的保护就再也拦不住这种文件
            volume = Math.multiplyExact(Math.multiplyExact((long) sizeX, (long) sizeY), (long) sizeZ);
            final long totalBits = Math.multiplyExact(volume, (long) bitsPerEntry);
            // +63 本身也可能溢出：totalBits 已经在 long 顶端时，回绕成负数会让「数组被截断」的检查失效
            requiredLongs = Math.addExact(totalBits, 63L) / 64L;
        }
        catch (ArithmeticException e)
        {
            LmpMod.LOGGER.warn("原理图 {} 的区域 {} 体积过大，拒绝解析", file, regionName);
            return Optional.empty();
        }

        if ((long) blockStates.length < requiredLongs)
        {
            LmpMod.LOGGER.warn("原理图 {} 的区域 {} 的 BlockStates 被截断：需要 {} 个 long，实际 {} 个",
                    file, regionName, requiredLongs, blockStates.length);
            return Optional.empty();
        }

        return Optional.of(new Region(regionName, positionX, positionY, positionZ,
                sizeX, sizeY, sizeZ, palette, blockStates, bitsPerEntry));
    }

    /**
     * 把「区域 pos1 相对偏移 + Size（各轴可正可负）」归一化成容器尺寸。
     *
     * <p>上游的 {@code getRelativeEndPositionFromAreaSize} 约定：另一端 = pos + (size >= 0 ? size-1 : size+1)，
     * 容器尺寸 = max - min + 1。正常文件 size 为正，结果就是 abs(size)。
     */
    private static int normalizeSize(int position, int size)
    {
        final long end = (long) position + (size >= 0 ? (long) size - 1L : (long) size + 1L);
        final long min = Math.min(position, end);
        final long max = Math.max(position, end);
        final long dimension = max - min + 1L;

        return (dimension <= 0L || dimension > Integer.MAX_VALUE) ? -1 : (int) dimension;
    }

    /**
     * 解析单个调色板条目。
     *
     * <p>Litematica 写的是大写 {@code Name}/{@code Properties}，原版 {@link NbtHelper#toBlockState}
     * 只认大写；上游读取器额外兼容小写 {@code name}/{@code properties}，这里也做同样兜底。
     *
     * @return 解析出的方块状态；{@code null} 表示条目缺失名字或名字无法解析（不可兼容）
     */
    private static BlockState decodePaletteEntry(NbtCompound entry)
    {
        if (entry == null || entry.isEmpty())
        {
            return null;
        }

        final NbtCompound normalized = normalizeKeys(entry);
        final String name = normalized.getString(State.NAME, "");

        if (name.isEmpty())
        {
            return null;
        }

        final BlockState state = NbtHelper.toBlockState(Registries.BLOCK, normalized);

        // 名字存在却解析成 AIR，说明该方块在当前注册表中不存在——这是「静默算错」的信号，必须拒绝。
        if (state.isAir() && !isAirBlockName(name))
        {
            return null;
        }

        return state;
    }

    /** 若条目用的是小写 {@code name}/{@code properties}，复制成原版认识的大写键。 */
    private static NbtCompound normalizeKeys(NbtCompound entry)
    {
        if (entry.contains(State.NAME) || !entry.contains(KEY_LOWER_NAME))
        {
            return entry;
        }

        final NbtCompound copy = new NbtCompound();
        copy.putString(State.NAME, entry.getString(KEY_LOWER_NAME, ""));

        final NbtElement properties = entry.get(KEY_LOWER_PROPERTIES);

        if (properties != null)
        {
            copy.put(State.PROPERTIES, properties);
        }

        return copy;
    }

    /**
     * 这个方块名是不是空气。
     *
     * <p>必须把带命名空间与不带命名空间的写法都列上：判据是「调色板里有名字却解析成 AIR 就拒绝」，
     * 而 {@code NbtHelper.toBlockState} 对没有冒号的名字会按 {@code minecraft} 命名空间解析。
     * 少列一个写法，一份完全合法的原理图就会被误拒。
     */
    private static boolean isAirBlockName(String name)
    {
        String path = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;

        return path.equals("air") || path.equals("cave_air") || path.equals("void_air");
    }

    /** 先按 gzip 读；失败再按未压缩读一次（少数工具写出的 NBT 未压缩）。 */
    private static NbtCompound readCompressedOrPlain(Path file) throws IOException
    {
        try
        {
            return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        }
        catch (IOException e)
        {
            return NbtIo.read(file);
        }
    }

    /**
     * 一张解析完成的原理图：格式版本、数据版本与所有区域。
     *
     * @param formatVersion        Litematica 原理图格式版本
     * @param minecraftDataVersion 文件记录的数据版本（已确认不低于当前运行版本）
     * @param regions              所有区域（只读）
     */
    public record Litematic(int formatVersion, int minecraftDataVersion, List<Region> regions)
    {
        public Litematic
        {
            regions = List.copyOf(regions);
        }
    }

    /**
     * 一个区域：容器尺寸、相对偏移，以及解包所需的调色板与打包后的位数组。
     *
     * <p>对外只暴露只读访问；位数组按需解包（{@link #stateAt(int, int, int)}），不在构造时展开，
     * 以免大区域占用额外内存。
     */
    public static final class Region
    {
        private final String name;
        private final int positionX;
        private final int positionY;
        private final int positionZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final BlockState[] palette;
        private final long[] blockStates;
        private final int bitsPerEntry;
        private final long volume;

        Region(String name, int positionX, int positionY, int positionZ,
               int sizeX, int sizeY, int sizeZ,
               BlockState[] palette, long[] blockStates, int bitsPerEntry)
        {
            this.name = name;
            this.positionX = positionX;
            this.positionY = positionY;
            this.positionZ = positionZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.palette = palette;
            this.blockStates = blockStates;
            this.bitsPerEntry = bitsPerEntry;
            this.volume = (long) sizeX * (long) sizeY * (long) sizeZ;
        }

        /** 区域名（Regions 的键）。 */
        public String name()
        {
            return this.name;
        }

        /** 区域相对原理图原点的 X 偏移（不是世界坐标）。 */
        public int positionX()
        {
            return this.positionX;
        }

        /** 区域相对原理图原点的 Y 偏移。 */
        public int positionY()
        {
            return this.positionY;
        }

        /** 区域相对原理图原点的 Z 偏移。 */
        public int positionZ()
        {
            return this.positionZ;
        }

        /** 归一化后的容器 X 尺寸。 */
        public int sizeX()
        {
            return this.sizeX;
        }

        /** 归一化后的容器 Y 尺寸。 */
        public int sizeY()
        {
            return this.sizeY;
        }

        /** 归一化后的容器 Z 尺寸。 */
        public int sizeZ()
        {
            return this.sizeZ;
        }

        /** 方块总数 {@code sizeX*sizeY*sizeZ}。 */
        public long volume()
        {
            return this.volume;
        }

        /** 每个条目占用的位数。 */
        public int bitsPerEntry()
        {
            return this.bitsPerEntry;
        }

        /** 调色板条目数。 */
        public int paletteSize()
        {
            return this.palette.length;
        }

        /** 调色板（下标即调色板 id）；调用方不得修改返回数组。 */
        public BlockState[] palette()
        {
            return this.palette;
        }

        /** 打包后的位数组；调用方不得修改返回数组。 */
        public long[] blockStates()
        {
            return this.blockStates;
        }

        /** 取某个局部坐标的方块状态；越界回落 AIR。 */
        public BlockState stateAt(int x, int y, int z)
        {
            if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ)
            {
                return Blocks.AIR.getDefaultState();
            }

            final long index = (long) y * (long) this.sizeX * (long) this.sizeZ
                    + (long) z * (long) this.sizeX + (long) x;

            return this.stateAtIndex(index);
        }

        /** 取线性下标处的方块状态（下标 = y*(sizeX*sizeZ) + z*sizeX + x）；越界或调色板 id 越界回落 AIR。 */
        public BlockState stateAtIndex(long index)
        {
            if (index < 0L || index >= this.volume)
            {
                return Blocks.AIR.getDefaultState();
            }

            final int paletteId = this.readPacked(index);

            if (paletteId < 0 || paletteId >= this.palette.length)
            {
                return Blocks.AIR.getDefaultState();
            }

            final BlockState state = this.palette[paletteId];

            return state == null ? Blocks.AIR.getDefaultState() : state;
        }

        /** 按 LitematicaBitArray 的公式取一个条目：低位在前、可跨 long、无对齐填充。 */
        private int readPacked(long index)
        {
            final long startOffset = index * (long) this.bitsPerEntry;
            final int startArrIndex = (int) (startOffset >> 6);
            final int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);
            final int startBitOffset = (int) (startOffset & 0x3FL);
            final long maxEntryValue = (1L << this.bitsPerEntry) - 1L;

            if (startArrIndex >= this.blockStates.length || endArrIndex >= this.blockStates.length)
            {
                return 0;
            }

            if (startArrIndex == endArrIndex)
            {
                return (int) ((this.blockStates[startArrIndex] >>> startBitOffset) & maxEntryValue);
            }

            final int endOffset = 64 - startBitOffset;
            final long value = (this.blockStates[startArrIndex] >>> startBitOffset
                    | this.blockStates[endArrIndex] << endOffset) & maxEntryValue;

            return (int) value;
        }
    }
}

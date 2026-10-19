package com.chenjicheng.lmp.region;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import org.jetbrains.annotations.Nullable;

/**
 * 一个「材料区」：某个共享原理图（placement）在某个维度里的一块长方体区域。
 *
 * <p>{@code pos1}/{@code pos2} 在构造时归一化为 min/max，因此调用方不必关心玩家先点了哪个角。
 * {@code revision} 是进度快照的版本号：任何一次区域变更以及服务器每次启动都必须递增，
 * 否则客户端会拿着与服务端相同的版本号渲染空白或旧区域的数据。
 *
 * @param id               区域自身的标识（协议里使用）
 * @param name             玩家可见的名字
 * @param dimension        维度标识符，例如 {@code minecraft:overworld}
 * @param pos1             归一化后的最小角
 * @param pos2             归一化后的最大角（含）
 * @param placementId      关联的 Syncmatica placement（可空：允许先划区域后绑定）
 * @param owner            创建者
 * @param createdAt        创建时间（epoch ms）
 * @param lastScanAt       上次扫描完成时间（epoch ms），0 表示从未扫描
 * @param revision         快照版本号
 * @param coveragePermille 上次扫描的区块覆盖率（千分比），1000 表示全部区块都已加载并扫描
 */
public record MaterialRegion(
        UUID id,
        String name,
        Identifier dimension,
        BlockPos pos1,
        BlockPos pos2,
        @Nullable UUID placementId,
        @Nullable UUID owner,
        long createdAt,
        long lastScanAt,
        long revision,
        int coveragePermille)
{
    /** 体积上限：防止误框出整个地图。超限直接拒绝，这是防 MSPT 的第一道闸。 */
    public static final long MAX_VOLUME = 1_000_000L;

    /** 覆盖的区块数上限（16×16 区块 = 256×256 格，足够一个仓库区）。 */
    public static final int MAX_CHUNKS = 256;

    /**
     * 名称长度上限。
     *
     * <p>名字来自客户端，而且会被原样塞进下发给所有玩家的区域清单（{@code writeString} 超过 32767
     * 就抛异常）。不设上限的话，一个超长名字能先把广播打崩，再让所有人的区域清单收不到。
     */
    public static final int MAX_NAME_LENGTH = 64;

    public MaterialRegion
    {
        // 必须先从原始值算出两个角，否则第二次比较会用到已被覆盖的 pos1
        BlockPos min = normalizeMin(pos1, pos2);
        BlockPos max = normalizeMax(pos1, pos2);
        pos1 = min;
        pos2 = max;
    }

    private static BlockPos normalizeMin(BlockPos a, BlockPos b)
    {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos normalizeMax(BlockPos a, BlockPos b)
    {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    /** 用于遍历/渲染的包围盒。 */
    public Box toBox()
    {
        return new Box(pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX() + 1, pos2.getY() + 1, pos2.getZ() + 1);
    }

    /**
     * 单轴跨度（含两端）。
     *
     * <p>必须用 long 计算：{@code pos2 - pos1} 在 int 域里会溢出——极端坐标下
     * {@code Integer.MAX_VALUE - Integer.MIN_VALUE + 1} 恰好回绕成 0，于是「整张地图那么大」的区域
     * 会被算成体积 0 并通过校验，最后在 {@link #chunks()} 处炸出负长度的数组。
     */
    private static long span(int low, int high)
    {
        return (long) high - (long) low + 1L;
    }

    /** 体积（格数，含两端）。超出上限的区域会被封顶，保证不溢出 long。 */
    public long volume()
    {
        long sx = Math.min(span(pos1.getX(), pos2.getX()), MAX_VOLUME + 1L);
        long sy = Math.min(span(pos1.getY(), pos2.getY()), MAX_VOLUME + 1L);
        long sz = Math.min(span(pos1.getZ(), pos2.getZ()), MAX_VOLUME + 1L);
        return sx * sy * sz;
    }

    /** 覆盖的区块数。用 long 计算，理由同 {@link #span(int, int)}。 */
    public long chunkCount()
    {
        long chunksX = span(pos1.getX() >> 4, pos2.getX() >> 4);
        long chunksZ = span(pos1.getZ() >> 4, pos2.getZ() >> 4);
        return chunksX * chunksZ;
    }

    public boolean contains(BlockPos pos)
    {
        return pos.getX() >= pos1.getX() && pos.getX() <= pos2.getX()
                && pos.getY() >= pos1.getY() && pos.getY() <= pos2.getY()
                && pos.getZ() >= pos1.getZ() && pos.getZ() <= pos2.getZ();
    }

    /**
     * 该区域覆盖的区块（按行优先顺序，扫描器按此顺序推进游标）。
     *
     * <p>只有通过 {@link #validate()} 的区域才允许走到这里；未校验的区域直接抛异常，
     * 而不是去分配一个长度荒谬（甚至为负）的数组。
     */
    public ChunkPos[] chunks()
    {
        long count = chunkCount();

        if (count <= 0L || count > MAX_CHUNKS)
        {
            throw new IllegalStateException("区域未经校验或过大：" + count + " 个区块（上限 " + MAX_CHUNKS + "）");
        }

        int minChunkX = pos1.getX() >> 4;
        int maxChunkX = pos2.getX() >> 4;
        int minChunkZ = pos1.getZ() >> 4;
        int maxChunkZ = pos2.getZ() >> 4;
        ChunkPos[] result = new ChunkPos[(int) count];
        int i = 0;

        for (int cz = minChunkZ; cz <= maxChunkZ; cz++)
        {
            for (int cx = minChunkX; cx <= maxChunkX; cx++)
            {
                result[i++] = new ChunkPos(cx, cz);
            }
        }

        return result;
    }

    public MaterialRegion withRevision(long newRevision)
    {
        return new MaterialRegion(id, name, dimension, pos1, pos2, placementId, owner, createdAt, lastScanAt, newRevision, coveragePermille);
    }

    public MaterialRegion withScanResult(long scanTime, long newRevision, int coverage)
    {
        return new MaterialRegion(id, name, dimension, pos1, pos2, placementId, owner, createdAt, scanTime, newRevision, coverage);
    }

    public MaterialRegion withPlacement(@Nullable UUID newPlacementId)
    {
        return new MaterialRegion(id, name, dimension, pos1, pos2, newPlacementId, owner, createdAt, lastScanAt, revision, coveragePermille);
    }

    /** 区域不可接受的原因。用枚举而不是字符串：i18n 键与参数个数必须一一对应，拼错了不会有人报错。 */
    public enum Invalid
    {
        TOO_LARGE("too_large"),
        TOO_MANY_CHUNKS("too_many_chunks"),
        EMPTY_NAME("empty_name"),
        NAME_TOO_LONG("name_too_long");

        private final String keySuffix;

        Invalid(String keySuffix)
        {
            this.keySuffix = keySuffix;
        }

        /** 对应的 i18n 键：{@code lmp.error.region.<suffix>}。 */
        public String key()
        {
            return "lmp.error.region." + this.keySuffix;
        }
    }

    /**
     * 校验区域是否可接受。
     *
     * @return 空表示通过；否则给出不可接受的原因
     */
    public Optional<Invalid> validate()
    {
        if (volume() > MAX_VOLUME)
        {
            return Optional.of(Invalid.TOO_LARGE);
        }

        if (chunkCount() > MAX_CHUNKS)
        {
            return Optional.of(Invalid.TOO_MANY_CHUNKS);
        }

        if (name == null || name.isBlank())
        {
            return Optional.of(Invalid.EMPTY_NAME);
        }

        if (name.length() > MAX_NAME_LENGTH)
        {
            return Optional.of(Invalid.NAME_TOO_LONG);
        }

        return Optional.empty();
    }

    /**
     * 一条拒绝理由：i18n 键 + 它的参数。
     *
     * <p>三个入口（控制台命令、协议处理、界面草稿）必须对同一条拒绝给出同样的说法，
     * 因此「哪个原因、几个参数」只在这里决定一次。
     */
    public record Rejection(String messageKey, List<Object> args)
    {
        /** 便于直接交给 {@code Text.translatable} / {@code StringUtils.translate}。 */
        public Object[] argsArray()
        {
            return this.args.toArray();
        }
    }

    /** 把 {@link #validate()} 给出的原因渲染成可翻译的消息。 */
    public Rejection rejection(Invalid invalid)
    {
        return switch (invalid)
        {
            case TOO_LARGE -> new Rejection(Invalid.TOO_LARGE.key(), List.of(this.volume(), MAX_VOLUME));
            case TOO_MANY_CHUNKS -> new Rejection(Invalid.TOO_MANY_CHUNKS.key(), List.of(this.chunkCount()));
            case EMPTY_NAME -> new Rejection(Invalid.EMPTY_NAME.key(), List.of());
            case NAME_TOO_LONG -> new Rejection(Invalid.NAME_TOO_LONG.key(), List.of(MAX_NAME_LENGTH));
        };
    }
}

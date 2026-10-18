package com.chenjicheng.lmp.region;

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

    public long volume()
    {
        return (long) (pos2.getX() - pos1.getX() + 1)
                * (pos2.getY() - pos1.getY() + 1)
                * (pos2.getZ() - pos1.getZ() + 1);
    }

    public int chunkCount()
    {
        int chunksX = (pos2.getX() >> 4) - (pos1.getX() >> 4) + 1;
        int chunksZ = (pos2.getZ() >> 4) - (pos1.getZ() >> 4) + 1;
        return chunksX * chunksZ;
    }

    public boolean contains(BlockPos pos)
    {
        return pos.getX() >= pos1.getX() && pos.getX() <= pos2.getX()
                && pos.getY() >= pos1.getY() && pos.getY() <= pos2.getY()
                && pos.getZ() >= pos1.getZ() && pos.getZ() <= pos2.getZ();
    }

    /** 该区域覆盖的区块（按行优先顺序，扫描器按此顺序推进游标）。 */
    public ChunkPos[] chunks()
    {
        int minChunkX = pos1.getX() >> 4;
        int maxChunkX = pos2.getX() >> 4;
        int minChunkZ = pos1.getZ() >> 4;
        int maxChunkZ = pos2.getZ() >> 4;
        ChunkPos[] result = new ChunkPos[(maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1)];
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

    /**
     * 校验区域是否可接受。
     *
     * @return 空表示通过；否则返回 i18n 键后缀（{@code lmp.error.region.<reason>}）
     */
    public Optional<String> validate()
    {
        if (volume() > MAX_VOLUME)
        {
            return Optional.of("too_large");
        }

        if (chunkCount() > MAX_CHUNKS)
        {
            return Optional.of("too_many_chunks");
        }

        if (name == null || name.isBlank())
        {
            return Optional.of("empty_name");
        }

        return Optional.empty();
    }
}

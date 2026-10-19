package com.chenjicheng.lmp.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.region.MaterialRegion;

import org.jetbrains.annotations.Nullable;

/**
 * 「正在编辑的那个材料区」的客户端草稿。
 *
 * <p>界面里玩家还没点保存之前，名称与两个角点只是本地状态。把这份状态单独拿出来有两个好处：
 * 一是界面代码只剩布局与事件；二是校验能在**发出请求之前**跑一遍，玩家看到的是「太大 / 名称为空」，
 * 而不是等一个来回之后才被告知失败。
 *
 * <p>判据与服务端共用 {@link MaterialRegion}，因此两边不可能对同一条区域给出不同的结论。
 * 服务端仍会独立校验一次——客户端校验只负责体验，不承担信任。
 */
public final class RegionDraft
{
    /** 范围还没填好时的拒绝键；它不属于 {@link MaterialRegion.Invalid}，因为服务端永远收得到两个角点。 */
    public static final String NO_RANGE = "lmp.error.region.no_range";

    private final UUID placementId;
    private final Identifier dimension;
    private String name;
    private @Nullable BlockPos pos1;
    private @Nullable BlockPos pos2;

    public RegionDraft(UUID placementId, Identifier dimension)
    {
        this(placementId, dimension, "");
    }

    public RegionDraft(UUID placementId, Identifier dimension, String name)
    {
        this.placementId = placementId;
        this.dimension = dimension;
        this.name = name;
    }

    /** 草稿属于哪个共享原理图（也是保存时自动绑定的 placement）。 */
    public UUID placementId()
    {
        return this.placementId;
    }

    public Identifier dimension()
    {
        return this.dimension;
    }

    public String name()
    {
        return this.name;
    }

    public String trimmedName()
    {
        return this.name.trim();
    }

    public void setName(String name)
    {
        this.name = name;
    }

    /** 两个角点是否都已设定。 */
    public boolean rangeSet()
    {
        return this.pos1 != null && this.pos2 != null;
    }

    /** 设定范围；玩家点两个角的顺序无关，这里统一归一化成 min/max。 */
    public void setCorners(BlockPos a, BlockPos b)
    {
        this.pos1 = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.pos2 = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    /** 归一化后的两个角点；范围未设定时为空。 */
    public Optional<BlockPos[]> corners()
    {
        return this.rangeSet() ? Optional.of(new BlockPos[] { this.pos1, this.pos2 }) : Optional.empty();
    }

    /** 体积（格数）；范围未设定时为 0。 */
    public long volume()
    {
        return this.rangeSet() ? this.toRegion().volume() : 0L;
    }

    /** 覆盖的区块数；范围未设定时为 0。 */
    public long chunkCount()
    {
        return this.rangeSet() ? this.toRegion().chunkCount() : 0L;
    }

    /**
     * 能不能保存。
     *
     * @return 空表示可以保存；否则给出拒绝理由（i18n 键 + 参数）
     */
    public Optional<MaterialRegion.Rejection> validate()
    {
        if (!this.rangeSet())
        {
            return Optional.of(new MaterialRegion.Rejection(NO_RANGE, List.of()));
        }

        MaterialRegion candidate = this.toRegion();
        return candidate.validate().map(candidate::rejection);
    }

    /**
     * 折算成区域对象，只用于本地计算与校验。
     *
     * <p>id / owner / 时间戳都是占位值：真正的取值由服务端决定，客户端算出来的这份不会被保存。
     */
    private MaterialRegion toRegion()
    {
        return new MaterialRegion(UUID.randomUUID(), this.trimmedName(), this.dimension,
                this.pos1, this.pos2, this.placementId, null, 0L, 0L, 0L, 0);
    }
}

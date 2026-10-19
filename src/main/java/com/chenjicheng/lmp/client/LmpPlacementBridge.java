package com.chenjicheng.lmp.client;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.client.gui.LmpProgressScreens;

import org.jetbrains.annotations.Nullable;

/**
 * 把 Litematica 的「放置」翻译成界面需要的纯数据。
 *
 * <p>为什么要翻译：设置界面只接受 {@code UUID + 名字 + 维度 + 包围盒}，不持有任何上游对象。
 * 这样界面就能在没有真实原理图的情况下被测试（客户端 GameTest 直接构造数据就能跑通保存流程），
 * 上游 API 变化也只影响这一个类。
 *
 * <p><b>可选依赖守卫</b>：本类直接引用 Litematica 的类，因此只能在 Litematica 存在时加载；
 * 入口 {@link #describe} 先确认 Litematica 与 Syncmatica 都在。Syncmatica 的引用隔离在
 * {@link SyncmaticaLookup} 里，未安装时那个嵌套类根本不会被加载。
 */
public final class LmpPlacementBridge
{
    /**
     * 一个已共享的放置。
     *
     * @param placementId Syncmatica 的放置标识（服务端据此找到 .litematic）
     * @param bounds      原理图的世界包围盒；Litematica 还没算出尺寸时为 {@code null}
     */
    public record Shared(UUID placementId, String name, Identifier dimension, @Nullable BlockPos[] bounds)
    {
    }

    private LmpPlacementBridge()
    {
    }

    /**
     * 描述一个放置；它不是共享放置（或依赖缺失、上游 API 变了）时返回空。
     *
     * <p>不抛异常：这个方法在放置列表逐行渲染的路径上被调用，抛出去就是整个界面打不开。
     */
    public static Optional<Shared> describe(SchematicPlacement placement)
    {
        if (!LmpProgressScreens.litematicaAvailable() || !LmpProgressScreens.syncmaticaAvailable())
        {
            return Optional.empty();
        }

        try
        {
            return SyncmaticaLookup.describe(placement);
        }
        catch (RuntimeException | LinkageError e)
        {
            LmpMod.LOGGER.warn("无法把放置「{}」解析成共享放置，材料进度入口对它不可用：{}",
                    placement.getName(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * 原理图包围盒归一化成 min/max；上游没算出尺寸时返回 {@code null}。
     *
     * <p>不能只读 {@code getEclosingBox()}：Syncmatica 渲染出来的放置没有算 enclosingBox（实测为
     * {@code null}），而它的子区域包围盒是齐的。没有尺寸，「用原理图范围」这个按钮就永远点不了，
     * 所以这里回退成「子区域包围盒取并集」——两种来源给的是同一个范围。
     */
    private static @Nullable BlockPos[] bounds(SchematicPlacement placement)
    {
        Box box = placement.getEclosingBox();

        if (box != null && box.getPos1() != null && box.getPos2() != null)
        {
            return normalize(box.getPos1(), box.getPos2());
        }

        BlockPos min = null;
        BlockPos max = null;

        for (Box subRegion : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values())
        {
            if (subRegion.getPos1() == null || subRegion.getPos2() == null)
            {
                continue;
            }

            BlockPos[] normalized = normalize(subRegion.getPos1(), subRegion.getPos2());
            min = min == null ? normalized[0] : min(min, normalized[0]);
            max = max == null ? normalized[1] : max(max, normalized[1]);
        }

        return min == null ? null : new BlockPos[] { min, max };
    }

    private static BlockPos[] normalize(BlockPos a, BlockPos b)
    {
        return new BlockPos[] {
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())) };
    }

    private static BlockPos min(BlockPos a, BlockPos b)
    {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b)
    {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    /** 唯一直接引用 Syncmatica 类的地方，只在确认模组已加载后才被调用（懒加载）。 */
    private static final class SyncmaticaLookup
    {
        private static Optional<Shared> describe(SchematicPlacement placement)
        {
            ch.endte.syncmatica.litematica.LitematicManager manager =
                    ch.endte.syncmatica.litematica.LitematicManager.getInstance();

            if (manager == null)
            {
                return Optional.empty();
            }

            // Syncmatica 用「正在渲染的放置」表来判断一个放置是不是共享放置（它自己的界面也这么判断）
            ch.endte.syncmatica.data.ServerPlacement shared = manager.getRenderingSyncmatic(placement);

            if (shared == null)
            {
                return Optional.empty();
            }

            Identifier dimension = Identifier.tryParse(shared.getDimension());

            if (dimension == null)
            {
                LmpMod.LOGGER.warn("共享放置「{}」的维度无法解析：{}", shared.getName(), shared.getDimension());
                return Optional.empty();
            }

            return Optional.of(new Shared(shared.getId(), placement.getName(), dimension, bounds(placement)));
        }
    }
}

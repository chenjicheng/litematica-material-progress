package com.chenjicheng.lmp.region;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.scan.ScanScheduler;

import org.jetbrains.annotations.Nullable;

/**
 * 区域编辑：界面与命令共用的服务端真相。
 *
 * <p>为什么单独一层：界面保存走 payload、控制台走命令。两条入口各写一套校验，迟早会出现
 * 「界面上不让存、命令能存进去」这种分叉。这里把校验、拒绝理由、落盘与扫描取消收在一处，
 * 两个入口只负责把结果渲染成人能看懂的话。
 *
 * <p>所有方法都必须在服务端主线程上调用：{@link RegionStore} 不是线程安全的，扫描调度器也是。
 */
public final class RegionEditor
{
    /**
     * 一次编辑的结果。
     *
     * @param ok         是否成功
     * @param messageKey 结果或失败原因的 i18n 键
     * @param args       与 {@code messageKey} 的占位符一一对应的参数
     * @param warningKey 成功但需要额外提醒的事（例如「改动在内存里，但写盘失败」）；没有则为 {@code null}
     */
    public record Result(boolean ok, String messageKey, List<Object> args, @Nullable String warningKey)
    {
        /** 结果文本；服务端与客户端都能用（不含渲染）。 */
        public Object[] argsArray()
        {
            return this.args.toArray();
        }
    }

    /** 落盘失败时的提醒键：改动在内存里成功，但重启后会消失。 */
    private static final String SAVE_FAILED = "lmp.msg.region.save_failed";

    private final RegionStore store;
    private final ScanScheduler scheduler;

    public RegionEditor(RegionStore store, ScanScheduler scheduler)
    {
        this.store = store;
        this.scheduler = scheduler;
    }

    /**
     * 新建或更新一个材料区。
     *
     * @param regionId    {@code null} 表示新建；非空表示更新该区域（不存在则拒绝）
     * @param name        玩家可见的名字；允许含空格，首尾空白会被去掉
     * @param placementId 绑定的共享放置。{@code null} 在新建时表示「暂不绑定」，
     *                    <b>在更新时表示「不动绑定」</b>——控制台的 {@code /lmp region create}
     *                    只写字面参数，不该因为没提 placement 就把已有的绑定抹掉
     * @param actor       发起者，用于记录创建者；控制台触发时为 {@code null}
     */
    public Result save(@Nullable UUID regionId, String name, Identifier dimension, BlockPos pos1, BlockPos pos2,
                       @Nullable UUID placementId, @Nullable UUID actor)
    {
        String trimmed = name == null ? "" : name.trim();
        Optional<MaterialRegion> existing = regionId == null ? Optional.empty() : this.store.byId(regionId);

        if (regionId != null && existing.isEmpty())
        {
            return failed("lmp.msg.region.not_found", trimmed);
        }

        // 更新时 placementId 为空 = 不动绑定：界面总会带上自己的 placement，
        // 而控制台只报字面参数，静默解绑属于用户没要求的破坏
        UUID binding = placementId != null ? placementId : existing.map(MaterialRegion::placementId).orElse(null);

        if (this.nameTaken(trimmed, regionId))
        {
            return failed("lmp.msg.region.exists", trimmed);
        }

        if (binding != null)
        {
            Optional<MaterialRegion> taken = this.store.byPlacement(binding)
                    .filter(region -> !region.id().equals(regionId));

            if (taken.isPresent())
            {
                // 「一个 placement 一个材料区」是设计要求，不是建议：两个区域绑同一个原理图时，
                // 玩家在界面上根本分不清哪份进度对应哪块地
                return failed("lmp.msg.region.placement_taken", taken.get().name(), binding.toString());
            }
        }

        MaterialRegion candidate = new MaterialRegion(
                regionId == null ? UUID.randomUUID() : regionId,
                trimmed,
                dimension,
                pos1,
                pos2,
                binding,
                existing.map(MaterialRegion::owner).orElse(actor),
                existing.map(MaterialRegion::createdAt).orElse(System.currentTimeMillis()),
                existing.map(MaterialRegion::lastScanAt).orElse(0L),
                0L,
                0);

        Optional<MaterialRegion.Invalid> invalid = candidate.validate();

        if (invalid.isPresent())
        {
            MaterialRegion.Rejection rejection = candidate.rejection(invalid.get());
            return failed(rejection.messageKey(), rejection.argsArray());
        }

        this.store.put(candidate);

        return persisted(existing.isPresent() ? "lmp.msg.region.updated" : "lmp.msg.region.created",
                candidate.name(), candidate.volume(), candidate.chunkCount());
    }

    /**
     * 按名字保存：名字已存在就更新那一条，否则新建。
     *
     * <p>给控制台用。玩家在界面上总是知道自己编辑的是哪一条（界面上就摆着那个区域），
     * 而控制台只有名字可写，所以这里替它把「新建还是更新」判断掉。
     *
     * <p>名字是<b>全局唯一</b>的键：同名的区域即使位于另一个维度，也会被当作要更新的那一条，
     * 于是它的维度会跟着这次调用改变。控制台入口因此不适合「在不同维度各建一个同名区域」这种用法——
     * 那种情况下请用不同的名字。
     */
    public Result saveByName(String name, Identifier dimension, BlockPos pos1, BlockPos pos2,
                             @Nullable UUID placementId, @Nullable UUID actor)
    {
        String trimmed = name == null ? "" : name.trim();
        String needle = trimmed.toLowerCase(Locale.ROOT);
        UUID existingId = this.store.all().stream()
                .filter(region -> region.name().toLowerCase(Locale.ROOT).equals(needle))
                .map(MaterialRegion::id)
                .findFirst()
                .orElse(null);

        return this.save(existingId, trimmed, dimension, pos1, pos2, placementId, actor);
    }

    /**
     * 删除一个材料区。
     *
     * <p>顺带取消它自己的扫描：不取消的话，扫描收尾会去写一个已经不存在的区域，而删除动作已经落盘，
     * 属于「删了又回来」的静默数据复活。
     */
    public Result delete(UUID regionId)
    {
        MaterialRegion region = this.store.byId(regionId).orElse(null);

        if (region == null)
        {
            return failed("lmp.msg.region.not_found", regionId.toString());
        }

        this.scheduler.cancelRegion(regionId);
        this.store.remove(regionId);
        return persisted("lmp.msg.region.deleted", region.name());
    }

    /**
     * 请求重扫某个材料区。
     *
     * @param actor 触发者，用于按玩家限流；{@code null} 表示系统触发
     */
    public Result rescan(UUID regionId, @Nullable UUID actor)
    {
        MaterialRegion region = this.store.byId(regionId).orElse(null);

        if (region == null)
        {
            return failed("lmp.msg.region.not_found", regionId.toString());
        }

        Optional<String> failure = this.scheduler.requestScan(region, actor);

        if (failure.isPresent())
        {
            return failed("lmp.msg.scan." + failure.get());
        }

        return success("lmp.msg.scan.started", region.name());
    }

    // ------------------------------------------------------------ 工具

    /** 是否已有另一个区域用了这个名字（忽略大小写）。 */
    private boolean nameTaken(String name, @Nullable UUID excludeId)
    {
        String needle = name.toLowerCase(Locale.ROOT);

        return this.store.all().stream()
                .filter(region -> excludeId == null || !region.id().equals(excludeId))
                .anyMatch(region -> region.name().toLowerCase(Locale.ROOT).equals(needle));
    }

    /** 写盘成功与否决定要不要追加一条提醒；内存里的改动无论如何都已经生效。 */
    private Result persisted(String messageKey, Object... args)
    {
        Result result = success(messageKey, args);
        return this.store.lastSaveSucceeded()
                ? result
                : new Result(true, result.messageKey(), result.args(), SAVE_FAILED);
    }

    private static Result success(String messageKey, Object... args)
    {
        return new Result(true, messageKey, List.of(args), null);
    }

    private static Result failed(String messageKey, Object... args)
    {
        return new Result(false, messageKey, List.of(args), null);
    }
}

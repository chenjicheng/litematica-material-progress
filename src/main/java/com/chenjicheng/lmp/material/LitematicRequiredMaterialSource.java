package com.chenjicheng.lmp.material;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.item.Item;

import com.chenjicheng.lmp.LmpMod;

/**
 * 服务端的「所需材料」实现：placementId → Syncmatica 保存的 {@code .litematic} → 全量所需材料。
 *
 * <p>三步各自都可能失败（Syncmatica 没装、placement 不存在、文件还没同步下来、文件损坏、
 * 方块名解析不了），任何一步失败都返回 {@link Optional#empty()} 表示<b>未知</b>——
 * 绝不用空清单冒充「什么都不需要」，否则界面会显示 0% 这种误导性数字。
 *
 * <p><b>调用线程</b>：由 {@code ScanScheduler} 在 {@code lmp-schematic-reader} 工作线程上调用，
 * 因此这里不访问世界，只读文件与注册表。缓存用 {@link ConcurrentHashMap} 而非
 * {@code computeIfAbsent}：解析可能上百毫秒，不该在 map 的桶锁里做。
 */
public final class LitematicRequiredMaterialSource implements RequiredMaterialSource
{
    /**
     * 失败结果的缓存时长。
     *
     * <p>为什么不永久缓存失败：Syncmatica 会先把 placement 广播给客户端/服务端，原理图文件随后才落盘，
     * 因此「暂时读不到」是正常时序。永久缓存会让该区域在本次运行内永远显示「所需未知」。
     * 而成功结果可以永久缓存——文件按内容哈希命名，内容一变就是另一个 placement。
     */
    private static final long FAILURE_TTL_MS = 60_000L;

    private record Cached(Optional<Map<Item, Integer>> value, long atMs)
    {
        boolean expired(long now)
        {
            return this.value.isEmpty() && now - this.atMs >= FAILURE_TTL_MS;
        }
    }

    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<Map<Item, Integer>> requiredFor(UUID placementId)
    {
        if (placementId == null)
        {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        Cached cached = this.cache.get(placementId);

        if (cached != null && !cached.expired(now))
        {
            return cached.value();
        }

        Optional<Map<Item, Integer>> parsed = this.parse(placementId);
        this.cache.put(placementId, new Cached(parsed, now));
        return parsed;
    }

    /** 丢弃缓存（诊断/测试用）；正常情况下文件内容变化会带来新的 placementId。 */
    public void invalidate()
    {
        this.cache.clear();
    }

    private Optional<Map<Item, Integer>> parse(UUID placementId)
    {
        Optional<Path> file = SchematicLocator.locate(placementId);

        if (file.isEmpty())
        {
            LmpMod.LOGGER.warn("找不到共享放置 {} 对应的 .litematic（Syncmatica 未安装、放置不存在或文件未同步），"
                    + "所需材料按未知处理", placementId);
            return Optional.empty();
        }

        Optional<LitematicReader.Litematic> schematic = LitematicReader.read(file.get());

        if (schematic.isEmpty())
        {
            LmpMod.LOGGER.warn("原理图 {} 无法解析，所需材料按未知处理", file.get());
            return Optional.empty();
        }

        Map<Item, Integer> required = RequiredMaterialCalculator.calculate(schematic.get());

        if (required.isEmpty())
        {
            // 全空的原理图会算出空清单，而空清单在界面上等于「0%」——比未知更误导
            LmpMod.LOGGER.warn("原理图 {} 算出的所需材料为空，按未知处理", file.get());
            return Optional.empty();
        }

        LmpMod.LOGGER.info("已解析原理图 {}：{} 种所需材料", file.get().getFileName(), required.size());
        return Optional.of(Map.copyOf(required));
    }
}

package com.chenjicheng.lmp.material;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.item.Item;

/**
 * 「某个共享原理图需要哪些材料」的来源。
 *
 * <p>M2 由 {@code LitematicRequiredMaterialSource} 实现：在服务端读取 Syncmatica 保存的 .litematic 并统计。
 *
 * <p><b>空返回表示「未知」而不是「什么都不需要」</b>：读不到或解析不了原理图时必须返回
 * {@link Optional#empty()}，调用方据此把进度标记为未知；若退化成空清单，界面会显示 0% 这种误导性数字。
 */
public interface RequiredMaterialSource
{
    /**
     * @param placementId Syncmatica 的共享放置标识
     * @return 所需材料（物品 → 数量）；{@link Optional#empty()} 表示无法确定
     */
    Optional<Map<Item, Integer>> requiredFor(UUID placementId);
}

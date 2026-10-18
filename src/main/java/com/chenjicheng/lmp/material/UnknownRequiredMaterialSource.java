package com.chenjicheng.lmp.material;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.item.Item;

/**
 * 「所需材料未知」的占位实现：在原理图解析器不可用（尚未接入、或服务端没有 Syncmatica）时使用。
 *
 * <p>它永远返回 {@link Optional#empty()}，界面因此显示「所需未知」而不是 0%——这是刻意的：
 * 没有任何数据时不能假装「什么都不缺」。
 */
public final class UnknownRequiredMaterialSource implements RequiredMaterialSource
{
    public static final UnknownRequiredMaterialSource INSTANCE = new UnknownRequiredMaterialSource();

    private UnknownRequiredMaterialSource()
    {
    }

    @Override
    public Optional<Map<Item, Integer>> requiredFor(UUID placementId)
    {
        return Optional.empty();
    }
}

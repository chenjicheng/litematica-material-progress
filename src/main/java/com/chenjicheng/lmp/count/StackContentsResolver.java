package com.chenjicheng.lmp.count;

import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * 扩展点：处理「非原版布局」的容器堆叠。
 *
 * <p>原版规则是「一个物品栈 = 一份组件数据」，所以一栈 64 个潜影盒共享同一份
 * {@code CONTAINER} 组件，内容乘以 {@code stack.getCount()} 即可。但把潜影盒改成可堆叠的模组
 * 可能改用别的布局，例如把 N 个盒子各自的内容存成组件里的一个列表（拆开时内容不能丢）。
 * 这类布局无法用通用规则识别，因此留出本接口：注册的解析器按顺序询问，
 * 第一个返回 {@code true} 的负责把数量写进 {@code out}。
 *
 * <p>实现约定：{@code multiplier} 是该物品栈代表的数量（即 {@code stack.getCount()}）；
 * 递归统计内部物品时必须把 {@code depth + 1} 传给
 * {@link ContainerCounter#countStack(ItemStack, Map, int)}，以免绕过深度保护。
 */
@FunctionalInterface
public interface StackContentsResolver
{
    /**
     * @return true 表示已处理（数量已写入 out），false 表示交给下一个解析器
     */
    boolean resolve(ItemStack stack, long multiplier, Map<Item, Long> out, int depth);
}

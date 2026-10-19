package com.chenjicheng.lmp.count;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * 把一个容器（或单个物品栈）的内容汇总为 {@code Item -> 数量}。
 *
 * <p>计数语义与 Litematica 的材料清单保持一致，这一点很关键——数字算错比不显示更糟：
 * <ul>
 *   <li>容器组件<b>存在且非空</b>：统计内部物品，<b>不</b>统计容器本身。</li>
 *   <li>容器组件缺失<b>或为空</b>：把容器物品本身按数量计入
 *       （原版每个潜影盒/收纳袋都带一个默认空组件，若照搬「遇到组件就返回」的写法，
 *       空盒子会被算成 0）。</li>
 *   <li><b>堆叠</b>：内部数量乘以 {@code stack.getCount()}。原版潜影盒不可堆叠，但模组可以让它堆叠，
 *       此时一栈就是 N 个盒子，内容必须乘 N。</li>
 * </ul>
 *
 * <p>本类不做任何世界访问，可在服务端主线程之外对「已拷贝的快照」安全调用。
 */
public final class ContainerCounter
{
    /** 递归深度上限：防御环形或异常嵌套数据（原版不允许潜影盒套潜影盒）。 */
    public static final int DEFAULT_MAX_DEPTH = 8;

    private static final List<StackContentsResolver> RESOLVERS = new CopyOnWriteArrayList<>();

    private ContainerCounter()
    {
    }

    /** 注册一个模组布局解析器；在默认规则之前生效。 */
    public static void registerResolver(StackContentsResolver resolver)
    {
        RESOLVERS.add(resolver);
    }

    /** 仅用于测试：清空已注册的解析器。 */
    public static void clearResolvers()
    {
        RESOLVERS.clear();
    }

    /** 统计整个容器的内容。 */
    public static Map<Item, Long> countContainer(Inventory inventory)
    {
        Map<Item, Long> counts = new LinkedHashMap<>();
        countContainer(inventory, counts);
        return counts;
    }

    /** 统计整个容器的内容并累加到 {@code out}。 */
    public static void countContainer(Inventory inventory, Map<Item, Long> out)
    {
        final int size = inventory.size();

        for (int slot = 0; slot < size; slot++)
        {
            countStack(inventory.getStack(slot), out, 0);
        }
    }

    /**
     * 统计单个物品栈。
     *
     * @param depth 当前递归深度，外部调用应为 0
     */
    public static void countStack(ItemStack stack, Map<Item, Long> out, int depth)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }

        // 模组堆叠：一个栈可能代表多个物品（例如可堆叠的潜影盒）
        final long multiplier = stack.getCount();

        if (multiplier <= 0L)
        {
            return;
        }

        if (depth < DEFAULT_MAX_DEPTH)
        {
            for (StackContentsResolver resolver : RESOLVERS)
            {
                if (resolver.resolve(stack, multiplier, out, depth))
                {
                    return;
                }
            }

            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);

            if (container != null)
            {
                Map<Item, Long> inner = countIterable(container.iterateNonEmpty(), depth);

                if (!inner.isEmpty())
                {
                    addScaled(inner, multiplier, out);
                    return;
                }
            }

            BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);

            if (bundle != null && !bundle.isEmpty())
            {
                addScaled(countIterable(bundle.iterate(), depth), multiplier, out);
                return;
            }
        }

        // 普通物品，或空容器（空潜影盒/空收纳袋算它本身）
        out.merge(stack.getItem(), multiplier, ContainerCounter::saturatingAdd);
    }

    private static Map<Item, Long> countIterable(Iterable<ItemStack> stacks, int depth)
    {
        Map<Item, Long> counts = new LinkedHashMap<>();

        for (ItemStack inner : stacks)
        {
            countStack(inner, counts, depth + 1);
        }

        return counts;
    }

    private static void addScaled(Map<Item, Long> inner, long multiplier, Map<Item, Long> out)
    {
        if (multiplier == 1L)
        {
            inner.forEach((item, count) -> out.merge(item, count, ContainerCounter::saturatingAdd));
            return;
        }

        inner.forEach((item, count) -> out.merge(item, saturatingMultiply(count, multiplier), ContainerCounter::saturatingAdd));
    }

    private static long saturatingMultiply(long a, long b)
    {
        if (a <= 0L || b <= 0L)
        {
            return 0L;
        }

        try
        {
            return Math.multiplyExact(a, b);
        }
        catch (ArithmeticException e)
        {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long a, long b)
    {
        try
        {
            return Math.addExact(a, b);
        }
        catch (ArithmeticException e)
        {
            return Long.MAX_VALUE;
        }
    }
}

package com.chenjicheng.lmp.material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.LeveledCauldronBlock;
import net.minecraft.block.MultifaceBlock;
import net.minecraft.block.SeaPickleBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.TurtleEggBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;

/**
 * 把解析后的原理图折算成「所需物品 → 数量」，镜像 Litematica 材料清单的语义。
 *
 * <p>换算规则（与上游 {@code MaterialCache}/{@code MaterialListUtils} 逐条对齐）：
 * <ul>
 *   <li>默认取 {@code state.getBlock().asItem().getDefaultStack()}；空物品（无物品形态的方块）直接丢弃，
 *       不产生 0 数量条目。</li>
 *   <li><b>含水方块</b>：先按其去掉 WATERLOGGED 的基础状态换算，再额外计 1 个水桶。</li>
 *   <li><b>双台阶</b>=2、<b>雪层</b>=LAYERS、<b>海龟蛋</b>=EGGS、<b>海泡菜</b>=PICKLES、
 *       <b>蜡烛</b>=CANDLES、<b>多面方块</b>=可用面数。</li>
 *   <li><b>门/床/高植株的上半</b>不计（否则每件会翻倍）。</li>
 *   <li><b>无物品方块</b>（活塞头/移动活塞/传送门/末地门）不计；<b>耕地</b>按泥土计；
 *       <b>水源/岩浆源</b>(LEVEL=0) 计 1 桶，流动水/岩浆不计。</li>
 *   <li><b>花盆</b>=花盆 + 盆栽物；<b>炼药锅</b>=炼药锅 +（岩浆桶/细雪桶/水桶或按等级的水瓶）。</li>
 * </ul>
 *
 * <p><b>潜影盒（用户重点）</b>：所需侧只按方块状态计 1 个潜影盒物品，<b>不</b>读 TileEntities 里的内容物。
 * 上游 Litematica 造材料清单也只遍历方块容器、从不看方块实体，所以原理图里的潜影盒内容物不是所需材料。
 * 只有「已收集」侧才需要展开内容物（且要乘 {@code stack.getCount()}）——两侧口径本就不对称，见
 * {@code ContainerCounter}。模组把潜影盒改成可堆叠也不会改变所需数量。
 *
 * <p><b>全量统计</b>：本类遍历原理图的<b>所有</b>区域。Syncmatica 服务端不同步子区域的启用状态，
 * 因此服务端只能按全量原理图统计——客户端若禁用了某些子区域，其界面所需会低于服务端结果，属预期差异。
 *
 * <p>本类只读方块状态，不访问世界，可在服务端 tick 线程之外安全调用。
 */
public final class RequiredMaterialCalculator
{
    private RequiredMaterialCalculator() {
    }

    /** 统计整张原理图所需的物品数量（累加所有区域）。 */
    public static Map<Item, Integer> calculate(LitematicReader.Litematic schematic)
    {
        final Map<BlockState, Long> stateCounts = new LinkedHashMap<>();

        for (LitematicReader.Region region : schematic.regions())
        {
            accumulateRegion(region, stateCounts);
        }

        final Map<Item, Integer> required = new LinkedHashMap<>();

        for (Map.Entry<BlockState, Long> entry : stateCounts.entrySet())
        {
            addState(required, entry.getKey(), entry.getValue());
        }

        return required;
    }

    /** 统计单个区域并累加到 {@code stateCounts}（按方块状态计数，含空气，空气随后被丢弃）。 */
    public static void accumulateRegion(LitematicReader.Region region, Map<BlockState, Long> stateCounts)
    {
        final long volume = region.volume();

        for (long index = 0L; index < volume; index++)
        {
            stateCounts.merge(region.stateAtIndex(index), 1L, Long::sum);
        }
    }

    /**
     * 把一个方块状态按 {@code count} 个折算成物品并累加到 {@code out}。
     *
     * <p>顺序与上游一致：先处理含水（额外水桶），再判断是否「多物品方块」，最后走单一物品分支。
     */
    static void addState(Map<Item, Integer> out, BlockState state, long count)
    {
        if (count <= 0L)
        {
            return;
        }

        final boolean waterlogged = state.contains(Properties.WATERLOGGED)
                && state.get(Properties.WATERLOGGED);
        final BlockState base = waterlogged ? state.with(Properties.WATERLOGGED, false) : state;

        if (waterlogged)
        {
            add(out, Items.WATER_BUCKET, count);
        }

        if (requiresMultipleItems(base))
        {
            for (ItemStack stack : multipleItemStacks(base))
            {
                if (!stack.isEmpty())
                {
                    add(out, stack.getItem(), count * (long) stack.getCount());
                }
            }
        }
        else
        {
            final ItemStack stack = singleItemStack(base);

            if (!stack.isEmpty())
            {
                add(out, stack.getItem(), count * (long) stack.getCount());
            }
        }
    }

    /** 是否是需要多种物品的方块（花盆、装填的炼药锅）。 */
    static boolean requiresMultipleItems(BlockState state)
    {
        final Block block = state.getBlock();

        if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT)
        {
            return true;
        }

        return block instanceof AbstractCauldronBlock && block != Blocks.CAULDRON;
    }

    /** 多物品方块对应的物品列表（可能含空物品，由调用方丢弃）。 */
    static List<ItemStack> multipleItemStacks(BlockState state)
    {
        final Block block = state.getBlock();

        if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT)
        {
            final Block content = ((FlowerPotBlock) block).getContent();

            return List.of(new ItemStack(Items.FLOWER_POT),
                    content == Blocks.AIR ? ItemStack.EMPTY : content.asItem().getDefaultStack());
        }

        if (block instanceof AbstractCauldronBlock && block != Blocks.CAULDRON)
        {
            if (block == Blocks.LAVA_CAULDRON)
            {
                return List.of(new ItemStack(Items.CAULDRON), new ItemStack(Items.LAVA_BUCKET));
            }

            if (block == Blocks.POWDER_SNOW_CAULDRON)
            {
                return List.of(new ItemStack(Items.CAULDRON), new ItemStack(Items.POWDER_SNOW_BUCKET));
            }

            if (block == Blocks.WATER_CAULDRON)
            {
                // 水瓶键忽略组件，因此与其它药水合并，符合上游 ItemType(checkNBT=false) 语义。
                return switch (state.get(LeveledCauldronBlock.LEVEL))
                {
                    case 1 -> List.of(new ItemStack(Items.CAULDRON), new ItemStack(Items.POTION));
                    case 2 -> List.of(new ItemStack(Items.CAULDRON),
                            new ItemStack(Items.POTION), new ItemStack(Items.POTION));
                    case 3 -> List.of(new ItemStack(Items.CAULDRON), new ItemStack(Items.WATER_BUCKET));
                    default -> List.of(new ItemStack(Items.CAULDRON));
                };
            }
        }

        return List.of(singleItemStack(state));
    }

    /** 单一物品方块的换算结果（可能是空栈，表示该方块不计）。 */
    static ItemStack singleItemStack(BlockState state)
    {
        final ItemStack override = stateToItemOverride(state);

        if (override != null)
        {
            if (override.isEmpty())
            {
                return ItemStack.EMPTY;
            }

            overrideStackSize(state, override);
            return override;
        }

        final ItemStack stack = state.getBlock().asItem().getDefaultStack();

        if (stack.isEmpty())
        {
            return ItemStack.EMPTY;
        }

        overrideStackSize(state, stack);
        return stack;
    }

    /** 显式覆盖：无物品方块返回空栈，特殊方块返回替换物品；无覆盖返回 null（走 asItem 兜底）。 */
    private static ItemStack stateToItemOverride(BlockState state)
    {
        final Block block = state.getBlock();

        if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON
                || block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
                || block == Blocks.END_GATEWAY)
        {
            return ItemStack.EMPTY;
        }

        if (block == Blocks.FARMLAND)
        {
            return new ItemStack(Items.DIRT);
        }

        if (block == Blocks.BROWN_MUSHROOM_BLOCK)
        {
            return new ItemStack(Blocks.BROWN_MUSHROOM_BLOCK.asItem());
        }

        if (block == Blocks.RED_MUSHROOM_BLOCK)
        {
            return new ItemStack(Blocks.RED_MUSHROOM_BLOCK.asItem());
        }

        if (block == Blocks.WATER)
        {
            return state.get(FluidBlock.LEVEL) == 0 ? new ItemStack(Items.WATER_BUCKET) : ItemStack.EMPTY;
        }

        if (block == Blocks.LAVA)
        {
            return state.get(FluidBlock.LEVEL) == 0 ? new ItemStack(Items.LAVA_BUCKET) : ItemStack.EMPTY;
        }

        if (block instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
        {
            return ItemStack.EMPTY;
        }

        if (block instanceof BedBlock && state.get(BedBlock.PART) == BedPart.HEAD)
        {
            return ItemStack.EMPTY;
        }

        if (block instanceof TallPlantBlock && state.get(TallPlantBlock.HALF) == DoubleBlockHalf.UPPER)
        {
            return ItemStack.EMPTY;
        }

        return null;
    }

    /** 覆盖单个方块对应的物品数量（双台阶、雪层、蛋/泡菜/蜡烛数量、多面方块面数）。 */
    private static void overrideStackSize(BlockState state, ItemStack stack)
    {
        final Block block = state.getBlock();

        if (block instanceof SlabBlock && state.get(SlabBlock.TYPE) == SlabType.DOUBLE)
        {
            stack.setCount(2);
        }
        else if (block == Blocks.SNOW)
        {
            stack.setCount(state.get(SnowBlock.LAYERS));
        }
        else if (block instanceof TurtleEggBlock)
        {
            stack.setCount(state.get(TurtleEggBlock.EGGS));
        }
        else if (block instanceof SeaPickleBlock)
        {
            stack.setCount(state.get(SeaPickleBlock.PICKLES));
        }
        else if (block instanceof CandleBlock)
        {
            stack.setCount(state.get(CandleBlock.CANDLES));
        }
        else if (block instanceof MultifaceBlock)
        {
            stack.setCount(MultifaceBlock.collectDirections(state).size());
        }
    }

    /** 饱和累加，防止异常大区域把 int 撑溢出。 */
    private static void add(Map<Item, Integer> out, Item item, long count)
    {
        if (count <= 0L)
        {
            return;
        }

        final long merged = (long) out.getOrDefault(item, 0) + count;
        out.put(item, merged >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) merged);
    }
}

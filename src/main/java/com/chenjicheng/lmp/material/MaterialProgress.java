package com.chenjicheng.lmp.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;

/**
 * 一个材料区的进度：所需（来自原理图）与已收集（来自区域内容器）的对照。
 *
 * <p>两个指标都要显示，因为它们回答不同的问题：
 * <ul>
 *   <li>{@link #typeProgress()} —— 完成种类 / 总种类：还有多少种材料没凑齐。</li>
 *   <li>{@link #itemProgress()} —— 已收集数量 / 所需数量：整体物资到位程度。</li>
 * </ul>
 * 数量进度按 {@code min(已收集, 所需)} 累加，因此多囤的材料不会让进度超过 100%。
 *
 * <p>{@link Status#UNKNOWN_REQUIRED} 表示服务端读不到或解析不了原理图：此时<b>不能</b>把所需当成 0，
 * 否则界面会显示「0%」这种误导性数字，必须由界面显示为未知状态。
 *
 * @param required         所需数量（按物品）
 * @param present          区域内已收集数量（按物品）
 * @param status           快照状态
 * @param coveragePermille 扫描覆盖的区块比例（千分比）
 * @param revision         对应的区域版本号
 */
public record MaterialProgress(
        Map<Item, Integer> required,
        Map<Item, Long> present,
        Status status,
        int coveragePermille,
        long revision)
{
    public enum Status
    {
        /** 所有区块都已加载并扫描。 */
        COMPLETE,
        /** 有区块未加载，结果不完整（仍可用，但界面要标注）。 */
        PARTIAL,
        /** 读不到原理图，所需材料未知。 */
        UNKNOWN_REQUIRED
    }

    public MaterialProgress
    {
        required = Map.copyOf(required);
        present = Map.copyOf(present);
    }

    public static MaterialProgress join(Map<Item, Integer> required, Map<Item, Long> present,
                                        Status status, int coveragePermille, long revision)
    {
        return new MaterialProgress(required, present, status, coveragePermille, revision);
    }

    /** 服务端读不到原理图时的快照：已收集仍然有效，所需未知。 */
    public static MaterialProgress unknownRequired(Map<Item, Long> present, int coveragePermille, long revision)
    {
        return new MaterialProgress(Map.of(), present, Status.UNKNOWN_REQUIRED, coveragePermille, revision);
    }

    public boolean hasRequired()
    {
        return this.status != Status.UNKNOWN_REQUIRED;
    }

    public int requiredFor(Item item)
    {
        return this.required.getOrDefault(item, 0);
    }

    public long presentFor(Item item)
    {
        return this.present.getOrDefault(item, 0L);
    }

    public long missingFor(Item item)
    {
        return Math.max(0L, (long) requiredFor(item) - presentFor(item));
    }

    public int totalTypes()
    {
        return this.required.size();
    }

    public int completedTypes()
    {
        int completed = 0;

        for (Map.Entry<Item, Integer> entry : this.required.entrySet())
        {
            if (presentFor(entry.getKey()) >= entry.getValue())
            {
                completed++;
            }
        }

        return completed;
    }

    public long totalRequired()
    {
        long total = 0L;

        for (int count : this.required.values())
        {
            total += count;
        }

        return total;
    }

    public long totalPresent()
    {
        long total = 0L;

        for (long count : this.present.values())
        {
            total += count;
        }

        return total;
    }

    /** 还差多少（只统计所需材料的缺口，多囤的部分不计）。 */
    public long totalMissing()
    {
        long total = 0L;

        for (Map.Entry<Item, Integer> entry : this.required.entrySet())
        {
            total += Math.max(0L, (long) entry.getValue() - presentFor(entry.getKey()));
        }

        return total;
    }

    /** 已收集但不在所需清单里的数量（界面可单独展示「额外囤积」）。 */
    public long totalExtra()
    {
        long total = 0L;

        for (Map.Entry<Item, Long> entry : this.present.entrySet())
        {
            if (!this.required.containsKey(entry.getKey()))
            {
                total += entry.getValue();
            }
        }

        return total;
    }

    /** 完成种类 / 总种类，取值 0..1；所需为空时返回 0。 */
    public double typeProgress()
    {
        int types = totalTypes();
        return types == 0 ? 0.0D : (double) completedTypes() / (double) types;
    }

    /**
     * 计入进度的已收集数量：按物品种类分别 {@code min(已收集, 所需)} 后求和。
     *
     * <p>多囤的材料不计入，所以这个数不会超过 {@link #totalRequired()}——
     * 界面显示「已收集/所需」时要用它，直接用 {@link #totalPresent()} 会出现 120%。
     */
    public long cappedPresent()
    {
        long collected = 0L;

        for (Map.Entry<Item, Integer> entry : this.required.entrySet())
        {
            collected += Math.min(presentFor(entry.getKey()), entry.getValue());
        }

        return collected;
    }

    /** 已收集数量 / 所需数量，取值 0..1（按 min 截断）；所需为 0 时返回 0。 */
    public double itemProgress()
    {
        long totalRequired = totalRequired();

        if (totalRequired <= 0L)
        {
            return 0.0D;
        }

        return (double) cappedPresent() / (double) totalRequired;
    }

    /** 界面用的行：按「还差多少」降序，其次按物品注册名，保证顺序稳定。 */
    public List<Row> rows()
    {
        List<Row> rows = new ArrayList<>(this.required.size());

        for (Map.Entry<Item, Integer> entry : this.required.entrySet())
        {
            rows.add(new Row(entry.getKey(), entry.getValue(), presentFor(entry.getKey())));
        }

        rows.sort(Comparator
                .comparingLong(Row::missing).reversed()
                .thenComparing(row -> row.item().toString()));

        return rows;
    }

    /** 区域里有、但原理图不需要的物品。 */
    public List<Row> extraRows()
    {
        List<Row> rows = new ArrayList<>();

        for (Map.Entry<Item, Long> entry : this.present.entrySet())
        {
            if (!this.required.containsKey(entry.getKey()))
            {
                rows.add(new Row(entry.getKey(), 0, entry.getValue()));
            }
        }

        rows.sort(Comparator
                .comparingLong(Row::present).reversed()
                .thenComparing(row -> row.item().toString()));

        return rows;
    }

    /** 便于按物品查表的拷贝。 */
    public Map<Item, Row> rowsByItem()
    {
        Map<Item, Row> map = new LinkedHashMap<>();

        for (Row row : rows())
        {
            map.put(row.item(), row);
        }

        return map;
    }

    public record Row(Item item, int required, long present)
    {
        public long missing()
        {
            return Math.max(0L, (long) this.required - this.present);
        }

        public boolean finished()
        {
            return this.present >= this.required;
        }

        /** 单行完成度，用于进度条；所需为 0（额外囤积）时返回 1。 */
        public double fraction()
        {
            return this.required <= 0 ? 1.0D : Math.min(1.0D, (double) this.present / (double) this.required);
        }
    }
}

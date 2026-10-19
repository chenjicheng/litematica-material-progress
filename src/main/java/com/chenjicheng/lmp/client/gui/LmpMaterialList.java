package com.chenjicheng.lmp.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.common.collect.ImmutableList;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.util.StringUtils;

import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.net.LmpClientNetworking;

/**
 * 把服务端快照适配成 Litematica 的材料清单（复用其 {@code GuiMaterialList} 界面）。
 *
 * <p>列映射：Total=所需、Missing=还差、Available=已收集、Wrong=0（本模组没有「方块状态不对」的概念）。
 * 上游那行汇总文本 "Total n / Done x% / Missing y%" 因此天然就是**数量进度**。
 *
 * <p>本类属于 Litematica 内部包的适配层，**只能在 Litematica 存在时加载**
 * （调用方 {@link LmpProgressScreens} 负责守卫），否则会 NoClassDefFoundError。
 */
public class LmpMaterialList extends MaterialListBase
{
    private final UUID regionId;
    private final String regionName;
    private ImmutableList<MaterialListEntry> safeCopy = ImmutableList.of();

    public LmpMaterialList(UUID regionId, String regionName)
    {
        this.regionId = regionId;
        this.regionName = regionName;
        this.reCreateMaterialList();
    }

    public UUID regionId()
    {
        return this.regionId;
    }

    public String regionName()
    {
        return this.regionName;
    }

    public MaterialProgress progress()
    {
        return ClientProgressStore.get().progress(this.regionId).orElse(null);
    }

    @Override
    public String getName()
    {
        return this.regionName;
    }

    @Override
    public String getTitle()
    {
        return StringUtils.translate("lmp.gui.title", this.regionName);
    }

    /**
     * 「刷新」= 重读客户端缓存 + 向服务端要一次快照，**绝不**在这里触发扫描。
     *
     * <p>是否真的重扫由服务端按快照 TTL 决定：界面上反复点刷新不会变成扫描风暴。
     * 玩家想强制重扫时用设置界面里的「重新扫描」（那条请求带 force 标记）。
     */
    @Override
    public void reCreateMaterialList()
    {
        MaterialProgress progress = this.progress();
        this.apply(progress);
        LmpClientNetworking.requestSnapshot(this.regionId, progress == null ? 0L : progress.revision(), false);
    }

    /** 用快照重建行；{@code null} 表示客户端还没有这个区域的数据。 */
    public void apply(MaterialProgress progress)
    {
        List<MaterialListEntry> entries = new ArrayList<>();

        if (progress != null)
        {
            for (MaterialProgress.Row row : progress.rows())
            {
                entries.add(toEntry(row.item(), row.required(), row.present()));
            }

            // 额外囤积（原理图不需要）也列出来，但 Total 为 0，一眼能分辨
            for (MaterialProgress.Row row : progress.extraRows())
            {
                entries.add(toEntry(row.item(), 0, row.present()));
            }
        }

        // 必须在 setMaterialListEntries 之前更新：它会回调 completionListener，界面随即读 getMaterialsAll()
        this.safeCopy = buildSafeCopy(entries);
        this.setMaterialListEntries(entries);
    }

    /**
     * 上游 {@code GuiMaterialList} 构造与材料清单 HUD 都会调用
     * {@code MaterialListUtils.updateAvailableCounts(getMaterialsAll(), player)}，
     * 把 Available 列覆盖成**玩家背包**里的数量。本模组的 Available 是「材料区内已收集」，
     * 两者含义不同，因此这里交出一份独立副本，让上游的改写落在副本上。
     */
    @Override
    public ImmutableList<MaterialListEntry> getMaterialsAll()
    {
        return this.safeCopy;
    }

    /**
     * 真实列表（也就是界面实际渲染的那份）里，某个物品显示的「已收集」数量；不在列表里返回 -1。
     *
     * <p>供诊断与客户端 GameTest 使用：上游会拿 {@link #getMaterialsAll()} 去覆盖 Available 列，
     * 这个口子读的是没被覆盖的那一份，因此能直接验证防护是否生效。
     */
    public int displayedCollected(Item item)
    {
        for (MaterialListEntry entry : this.materialListAll)
        {
            if (entry.getStack().isOf(item))
            {
                return entry.getCountAvailable();
            }
        }

        return -1;
    }

    private static ImmutableList<MaterialListEntry> buildSafeCopy(List<MaterialListEntry> entries)
    {
        List<MaterialListEntry> copy = new ArrayList<>(entries.size());

        for (MaterialListEntry entry : entries)
        {
            copy.add(new MaterialListEntry(entry.getStack(), entry.getCountTotal(), entry.getCountMissing(),
                    entry.getCountMismatched(), entry.getCountAvailable()));
        }

        return ImmutableList.copyOf(copy);
    }

    private static MaterialListEntry toEntry(Item item, int required, long present)
    {
        // MaterialListEntry 只接受 int；服务端计数用 long，超界时截断（实际不可能达到）
        int available = (int) Math.min(present, Integer.MAX_VALUE);
        int missing = (int) Math.min(Math.max(0L, (long) required - present), Integer.MAX_VALUE);
        return new MaterialListEntry(new ItemStack(item), required, missing, 0, available);
    }
}

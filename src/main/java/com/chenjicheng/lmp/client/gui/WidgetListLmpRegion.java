package com.chenjicheng.lmp.client.gui;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 材料区清单的数据源。
 *
 * <p>内容直接取客户端缓存（服务端推来的区域清单），因此服务端一改，重开界面就是最新的。
 * 用 malilib 的列表基类而不是自己摆一排按钮：区域数量没有上限，列表自带滚动与搜索框。
 */
public class WidgetListLmpRegion extends WidgetListBase<LmpPayloads.RegionInfo, WidgetLmpRegionEntry>
{
    public WidgetListLmpRegion(int x, int y, int width, int height, ISelectionListener<LmpPayloads.RegionInfo> listener)
    {
        super(x, y, width, height, listener);
        this.browserEntryHeight = 22;
    }

    @Override
    protected Collection<LmpPayloads.RegionInfo> getAllEntries()
    {
        return ClientProgressStore.get().regions();
    }

    @Override
    protected List<String> getEntryStringsForFilter(LmpPayloads.RegionInfo entry)
    {
        return List.of(entry.name().toLowerCase(Locale.ROOT), entry.dimension().toLowerCase(Locale.ROOT));
    }

    @Override
    protected WidgetLmpRegionEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
                                                         LmpPayloads.RegionInfo entry)
    {
        return new WidgetLmpRegionEntry(x, y, this.browserEntryWidth, this.getBrowserEntryHeightFor(entry),
                entry, listIndex);
    }
}

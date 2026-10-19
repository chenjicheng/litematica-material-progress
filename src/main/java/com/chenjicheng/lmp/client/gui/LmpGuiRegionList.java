package com.chenjicheng.lmp.client.gui;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 材料区清单界面。
 *
 * <p>它是热键 K 在「脚下没有材料区、当前维度又不止一个」时的落点：以前这里往聊天栏里刷一行行
 * 带点击命令的文字，而命令行入口已经删掉，改成正经的界面。空清单时给一句指路的话——
 * 玩家第一次打开它多半就是还没建过任何材料区。
 */
public class LmpGuiRegionList extends GuiListBase<LmpPayloads.RegionInfo, WidgetLmpRegionEntry, WidgetListLmpRegion>
        implements ISelectionListener<LmpPayloads.RegionInfo>
{
    public LmpGuiRegionList()
    {
        super(10, 30);
        this.setTitle(StringUtils.translate("lmp.gui.list.title"));
    }

    @Override
    protected WidgetListLmpRegion createListWidget(int listX, int listY)
    {
        return new WidgetListLmpRegion(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.width - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return this.height - 50;
    }

    @Override
    protected ISelectionListener<LmpPayloads.RegionInfo> getSelectionListener()
    {
        return this;
    }

    /** 选中一行没有额外含义：动作在行内自己的按钮上。 */
    @Override
    public void onSelectionChange(LmpPayloads.RegionInfo entry)
    {
    }

    @Override
    public void drawContents(GuiContext context, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(context, mouseX, mouseY, partialTicks);

        if (ClientProgressStore.get().regions().isEmpty())
        {
            this.drawString(context, StringUtils.translate("lmp.gui.list.empty"), 12, this.height - 30, 0xFFFFC040);
        }
    }
}

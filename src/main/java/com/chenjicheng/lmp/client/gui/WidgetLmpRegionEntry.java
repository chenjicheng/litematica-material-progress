package com.chenjicheng.lmp.client.gui;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 材料区清单里的一行：名字、维度、体积，以及「打开进度」。
 *
 * <p>按钮放在行右侧，位置由行宽算出来——列表宽度随窗口变化，写死坐标会在窄窗口里跑到屏幕外。
 */
public class WidgetLmpRegionEntry extends WidgetListEntryBase<LmpPayloads.RegionInfo>
{
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFB0B0B0;

    private final LmpPayloads.RegionInfo region;
    private final boolean isOdd;

    public WidgetLmpRegionEntry(int x, int y, int width, int height, LmpPayloads.RegionInfo region, int listIndex)
    {
        super(x, y, width, height, region, listIndex);
        this.region = region;
        this.isOdd = (listIndex % 2) != 0;

        ButtonGeneric open = new ButtonGeneric(x + width - 4, y + 1, -1, true, "lmp.gui.list.open");
        this.addButton(open, (button, mouseButton) -> LmpProgressScreens.open(this.region.id(), this.region.name()));
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected)
    {
        RenderUtils.drawRect(context, this.x, this.y, this.x + this.width, this.y + this.height,
                this.isOdd ? 0x18FFFFFF : 0x28FFFFFF);

        int textX = this.x + 4;
        int textY = this.y + 6;
        this.drawString(context, textX, textY, TEXT_COLOR, this.region.name());
        textX += this.getStringWidth(this.region.name()) + 12;
        this.drawString(context, textX, textY, HINT_COLOR, this.region.dimension());
        textX += this.getStringWidth(this.region.dimension()) + 12;
        this.drawString(context, textX, textY, HINT_COLOR,
                StringUtils.translate("lmp.gui.list.volume", this.region.volume()));

        super.render(context, mouseX, mouseY, selected);
    }
}

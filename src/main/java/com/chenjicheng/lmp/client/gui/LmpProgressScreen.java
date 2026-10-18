package com.chenjicheng.lmp.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.net.LmpClientNetworking;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 没有 Litematica 时的自绘进度界面。
 *
 * <p>功能上与复用 {@code GuiMaterialList} 的那条路径等价：两个进度维度、逐物品的所需/已收集/还差、
 * 额外囤积、扫描进度、强制重扫。存在这条路径的理由是「可选依赖」必须真的可选——
 * 装了本模组但没装 Litematica 的客户端也要能用。
 */
public class LmpProgressScreen extends Screen
{
    private static final int LIST_TOP = 64;
    private static final int LIST_BOTTOM_MARGIN = 44;
    private static final int ROW_HEIGHT = 20;
    private static final int COLOR_LABEL = 0xFFA0A0A0;
    private static final int COLOR_VALUE = 0xFFFFFFFF;
    private static final int COLOR_DONE = 0xFF55FF55;
    private static final int COLOR_MISSING = 0xFFFF7070;
    private static final int COLOR_SECTION = 0xFFFFD070;

    private final UUID regionId;
    private final Runnable storeListener = this::onStoreChanged;
    private boolean listening;
    private RowListWidget list;

    public LmpProgressScreen(UUID regionId)
    {
        super(Text.translatable("lmp.gui.title", regionName(regionId)));
        this.regionId = regionId;
    }

    private static String regionName(UUID regionId)
    {
        return ClientProgressStore.get().regionById(regionId)
                .map(LmpPayloads.RegionInfo::name)
                .orElse("-");
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void init()
    {
        ClientProgressStore store = ClientProgressStore.get();

        if (!this.listening)
        {
            store.addListener(this.storeListener);
            this.listening = true;
        }

        this.list = new RowListWidget(this.client, this.width,
                Math.max(ROW_HEIGHT, this.height - LIST_TOP - LIST_BOTTOM_MARGIN), LIST_TOP, ROW_HEIGHT);
        this.addDrawableChild(this.list);

        this.rebuildRows();
        this.createButtons();
        this.requestSnapshot(false);
    }

    private void createButtons()
    {
        int y = this.height - 30;
        int centre = this.width / 2;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("lmp.gui.button.refresh"),
                        button -> this.requestSnapshot(true))
                .dimensions(centre - 154, y, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("lmp.gui.button.next_region"),
                        button -> this.openNextRegion())
                .dimensions(centre - 50, y, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("lmp.gui.button.close"),
                        button -> this.close())
                .dimensions(centre + 54, y, 100, 20).build());
    }

    @Override
    public void removed()
    {
        if (this.listening)
        {
            ClientProgressStore.get().removeListener(this.storeListener);
            this.listening = false;
        }

        super.removed();
    }

    /** 扫描进度会持续变化，界面暂停会把单机服务端一起冻住，反而看不到更新。 */
    @Override
    public boolean shouldPause()
    {
        return false;
    }

    // ------------------------------------------------------------ 数据

    private void onStoreChanged()
    {
        this.rebuildRows();
    }

    private void requestSnapshot(boolean force)
    {
        long revision = ClientProgressStore.get().progress(this.regionId)
                .map(MaterialProgress::revision)
                .orElse(0L);
        LmpClientNetworking.requestSnapshot(this.regionId, revision, force);
    }

    private void openNextRegion()
    {
        List<LmpPayloads.RegionInfo> candidates = this.candidates();

        if (candidates.size() < 2)
        {
            return;
        }

        int index = 0;

        for (int i = 0; i < candidates.size(); i++)
        {
            if (candidates.get(i).id().equals(this.regionId))
            {
                index = i;
                break;
            }
        }

        LmpPayloads.RegionInfo next = candidates.get((index + 1) % candidates.size());
        this.client.setScreen(new LmpProgressScreen(next.id()));
    }

    /** 当前维度里的材料区；一个都没有时退回全部（区域可能划在别的维度）。 */
    private List<LmpPayloads.RegionInfo> candidates()
    {
        List<LmpPayloads.RegionInfo> all = ClientProgressStore.get().regions();

        if (this.client == null || this.client.world == null)
        {
            return all;
        }

        Identifier dimension = this.client.world.getRegistryKey().getValue();
        List<LmpPayloads.RegionInfo> here = new ArrayList<>();

        for (LmpPayloads.RegionInfo info : all)
        {
            if (dimension.toString().equals(info.dimension()))
            {
                here.add(info);
            }
        }

        return here.isEmpty() ? all : here;
    }

    private void rebuildRows()
    {
        if (this.list == null)
        {
            return;
        }

        MaterialProgress progress = ClientProgressStore.get().progress(this.regionId).orElse(null);
        List<Row> rows = new ArrayList<>();

        if (progress != null)
        {
            for (MaterialProgress.Row row : progress.rows())
            {
                rows.add(Row.item(row.item(), row.required(), row.present()));
            }

            List<MaterialProgress.Row> extra = progress.extraRows();

            if (!extra.isEmpty())
            {
                rows.add(Row.section("lmp.gui.section.extra"));

                for (MaterialProgress.Row row : extra)
                {
                    rows.add(Row.item(row.item(), 0, row.present()));
                }
            }
        }

        this.list.setRows(rows);
    }

    // ------------------------------------------------------------ 绘制

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks)
    {
        super.render(context, mouseX, mouseY, deltaTicks);
        this.drawHeader(context);
    }

    private void drawHeader(DrawContext context)
    {
        ClientProgressStore store = ClientProgressStore.get();
        LmpPayloads.RegionInfo info = store.regionById(this.regionId).orElse(null);
        MaterialProgress progress = store.progress(this.regionId).orElse(null);
        ClientProgressStore.Status status = store.status(this.regionId).orElse(null);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, COLOR_VALUE);

        if (info != null)
        {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("lmp.gui.header.region",
                    info.dimension(), info.volume()), 12, 28, COLOR_LABEL);
        }

        if (!store.serverSupportsUs())
        {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("lmp.gui.status.server_missing"),
                    12, 40, COLOR_MISSING);
            return;
        }

        if (status != null && status.phase() != ClientProgressStore.Phase.IDLE)
        {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("lmp.gui.status.scanning",
                    String.format("%.0f", status.fraction() * 100.0D), status.chunksDone(), status.chunksTotal()),
                    12, 40, COLOR_SECTION);
            return;
        }

        if (progress == null)
        {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("lmp.gui.status.no_data"),
                    12, 40, COLOR_LABEL);
            return;
        }

        if (!progress.hasRequired())
        {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("lmp.gui.status.unknown_required"),
                    12, 40, COLOR_MISSING);
            return;
        }

        String types = String.format("%s  %s",
                Text.translatable("lmp.gui.progress.types",
                        String.format("%.1f", progress.typeProgress() * 100.0D),
                        progress.completedTypes(), progress.totalTypes()).getString(),
                Text.translatable("lmp.gui.progress.items",
                        String.format("%.1f", progress.itemProgress() * 100.0D),
                        progress.cappedPresent(), progress.totalRequired()).getString());
        context.drawTextWithShadow(this.textRenderer, types, 12, 40, COLOR_VALUE);

        if (progress.status() == MaterialProgress.Status.PARTIAL)
        {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("lmp.gui.status.partial",
                    progress.coveragePermille()), 12, 52, COLOR_SECTION);
        }
        else
        {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("lmp.gui.status.full_schematic"),
                    12, 52, COLOR_LABEL);
        }
    }

    // ------------------------------------------------------------ 行模型

    private record Row(Item item, int required, long present, String sectionKey)
    {
        static Row item(Item item, int required, long present)
        {
            return new Row(item, required, present, null);
        }

        static Row section(String key)
        {
            return new Row(null, 0, 0L, key);
        }

        boolean isSection()
        {
            return this.sectionKey != null;
        }

        long missing()
        {
            return Math.max(0L, (long) this.required - this.present);
        }
    }

    private static final class RowListWidget extends EntryListWidget<RowListWidget.RowEntry>
    {
        RowListWidget(MinecraftClient client, int width, int height, int y, int itemHeight)
        {
            super(client, width, height, y, itemHeight);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder)
        {
            this.appendDefaultNarrations(builder);
        }

        void setRows(List<Row> rows)
        {
            List<RowEntry> entries = new ArrayList<>(rows.size());

            for (Row row : rows)
            {
                entries.add(new RowEntry(row));
            }

            this.replaceEntries(entries);
        }

        static final class RowEntry extends EntryListWidget.Entry<RowEntry>
        {
            private final Row row;

            RowEntry(Row row)
            {
                this.row = row;
            }

            @Override
            public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float deltaTicks)
            {
                int left = this.getContentX();
                int top = this.getContentY();
                int right = this.getContentRightEnd();
                int textY = top + (this.getContentHeight() - 8) / 2;

                if (this.row.isSection())
                {
                    context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                            Text.translatable(this.row.sectionKey()), left + 2, textY, COLOR_SECTION);
                    return;
                }

                ItemStack stack = new ItemStack(this.row.item());
                context.drawItem(stack, left + 2, top + 2);
                context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                        stack.getName(), left + 24, textY, COLOR_VALUE);

                long missing = this.row.missing();
                int color = missing == 0L ? COLOR_DONE : COLOR_MISSING;
                int columnX = right - 186;
                context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                        Text.translatable("lmp.gui.column.required", this.row.required()), columnX, textY, COLOR_LABEL);
                context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                        Text.translatable("lmp.gui.column.present", this.row.present()), columnX + 62, textY, COLOR_VALUE);
                context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                        Text.translatable("lmp.gui.column.missing", missing), columnX + 124, textY, color);
            }
        }
    }
}

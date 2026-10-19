package com.chenjicheng.lmp.client.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.util.StringUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.client.RegionDraft;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.net.LmpClientNetworking;
import com.chenjicheng.lmp.net.LmpPayloads;
import com.chenjicheng.lmp.region.MaterialRegion;

import org.jetbrains.annotations.Nullable;

/**
 * 「材料收集进度设置」：为一个已共享的原理图划定材料区。
 *
 * <p>这是玩家的主要入口——从放置列表里那个共享原理图行上的按钮进来。因此它同时承担四件事：
 * 说清楚现在的状态（有没有材料区、扫没扫过、覆盖多少）、提供两种取范围方式（原理图范围 / 当前选区）、
 * 提交保存与删除、以及跳到进度界面。
 *
 * <p>界面只提交意图：本地校验只是为了少一次往返，服务端仍会独立校验权限与区域约束，
 * 结果以聊天栏消息回到玩家眼前。因此这里不说「保存成功」，只说「已提交」。
 *
 * <p>按钮的动作用公开方法表达，监听器只是转发——这样界面行为既能被鼠标点到，
 * 也能被客户端 GameTest 直接调用，不必为了测试去模拟鼠标轨迹。
 */
public class LmpGuiPlacementSettings extends GuiBase
{
    private static final int MARGIN = 12;
    private static final int LABEL_WIDTH = 70;
    private static final int ROW_HEIGHT = 22;
    private static final int GAP = 4;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFAAAAAA;

    private final UUID placementId;
    private final String initialName;
    private final Identifier dimension;
    private final @Nullable BlockPos[] schematicBounds;
    private final RegionDraft draft;
    private final Map<String, ButtonGeneric> buttonsByKey = new HashMap<>();
    private final Runnable storeListener = this::onStoreChanged;
    private @Nullable UUID regionId;
    private @Nullable GuiTextFieldGeneric nameField;
    private @Nullable ClientProgressStore.Phase lastPhase;
    /** 本界面刚提交过一次保存：用来把「服务端广播回来的是我刚建的区域」与「别人建的」区分开。 */
    private boolean submitted;

    /**
     * @param placementId     所属的共享放置；保存时会自动绑定它
     * @param placementName   显示名，同时作为材料区名字的默认值
     * @param dimension       该放置所在的维度
     * @param schematicBounds 原理图包围盒；未知时传 {@code null}，界面上对应的取范围按钮会禁用
     */
    public LmpGuiPlacementSettings(UUID placementId, String placementName, Identifier dimension,
                                   @Nullable BlockPos[] schematicBounds)
    {
        this.placementId = placementId;
        this.initialName = placementName;
        this.dimension = dimension;
        this.schematicBounds = schematicBounds;
        this.draft = new RegionDraft(placementId, dimension, placementName);
        this.adoptStoredRegion();
        this.setTitle(StringUtils.translate("lmp.gui.setup.title", placementName));
        // 必须在这里注册一次，而不是在 initGui() 里：initGui 会被 refresh() 与窗口缩放反复调用，
        // 每次都注册会让回调次数随开关次数增长，而 removed() 只注销得掉其中一个
        ClientProgressStore.get().addListener(this.storeListener);
    }

    /** 本界面正在编辑的区域 id；还没保存过时为 {@code null}。供诊断与 GameTest 使用。 */
    public @Nullable UUID regionId()
    {
        return this.regionId;
    }

    /** 本界面的范围草稿。供诊断与 GameTest 使用。 */
    public RegionDraft draft()
    {
        return this.draft;
    }

    /** 重读输入框并重建界面。供诊断与 GameTest 使用（界面内部在取范围后自己会调）。 */
    public void refreshView()
    {
        this.refresh();
    }

    /** 按 i18n 键取按钮；供诊断与 GameTest 使用（界面自己不需要这个查找）。 */
    public @Nullable ButtonBase buttonFor(String translationKey)
    {
        return this.buttonsByKey.get(translationKey);
    }

    // ------------------------------------------------------------ 布局

    @Override
    public void initGui()
    {
        super.initGui();

        this.buttonsByKey.clear();

        int x = MARGIN;
        int y = 26;

        // 名称
        this.addLabel(x, y, LABEL_WIDTH, 12, TEXT_COLOR, StringUtils.translate("lmp.gui.setup.label.name"));
        GuiTextFieldGeneric field = new GuiTextFieldGeneric(x + LABEL_WIDTH, y - 4, 200, 18, this.font);
        field.setText(this.draft.name());
        field.setMaxLengthWrapper(MaterialRegion.MAX_NAME_LENGTH);
        this.addTextField(field, new NameListener());
        this.nameField = field;
        y += ROW_HEIGHT + GAP;

        // 范围
        this.addLabel(x, y, LABEL_WIDTH, 12, TEXT_COLOR, StringUtils.translate("lmp.gui.setup.label.range"));
        this.addLabel(x + LABEL_WIDTH, y, 320, 12, TEXT_COLOR, this.rangeText());
        y += 12;
        this.addLabel(x + LABEL_WIDTH, y, 320, 12, HINT_COLOR, this.sizeText());
        y += ROW_HEIGHT;

        // 状态
        this.addLabel(x, y, LABEL_WIDTH, 12, TEXT_COLOR, StringUtils.translate("lmp.gui.setup.label.status"));
        this.addLabel(x + LABEL_WIDTH, y, 320, 12, TEXT_COLOR, this.statusText());
        y += ROW_HEIGHT + GAP;

        // 取范围
        int buttonX = x + LABEL_WIDTH;

        ButtonGeneric useBounds = this.addKeyedButton(buttonX, y, "lmp.gui.setup.use_schematic_bounds",
                (button, mouse) -> this.applySchematicBounds());
        useBounds.setEnabled(this.schematicBounds != null);
        useBounds.setHoverStrings(StringUtils.translate(this.schematicBounds != null
                ? "lmp.gui.setup.use_schematic_bounds.hover"
                : "lmp.gui.setup.no_bounds"));
        buttonX += useBounds.getWidth() + GAP;

        ButtonGeneric useSelection = this.addKeyedButton(buttonX, y, "lmp.gui.setup.use_selection",
                (button, mouse) -> this.applySelection());
        boolean selectionUsable = LmpProgressScreens.litematicaAvailable() && this.inPlacementDimension();
        useSelection.setEnabled(selectionUsable);
        useSelection.setHoverStrings(StringUtils.translate(selectionUsable
                ? "lmp.gui.setup.use_selection.hover"
                : "lmp.gui.setup.use_selection.unavailable"));
        y += ROW_HEIGHT + GAP;

        // 保存 / 删除
        buttonX = x + LABEL_WIDTH;
        ButtonGeneric save = this.addKeyedButton(buttonX, y, "lmp.gui.setup.save", (button, mouse) -> this.save());
        buttonX += save.getWidth() + GAP;

        ButtonGeneric delete = this.addKeyedButton(buttonX, y, "lmp.gui.setup.delete",
                (button, mouse) -> this.deleteRegion());
        delete.setEnabled(this.regionId != null);
        y += ROW_HEIGHT + GAP;

        // 打开进度 / 重新扫描
        buttonX = x + LABEL_WIDTH;
        ButtonGeneric progress = this.addKeyedButton(buttonX, y, "lmp.gui.setup.open_progress",
                (button, mouse) -> this.openProgress());
        progress.setEnabled(this.regionId != null);
        buttonX += progress.getWidth() + GAP;

        ButtonGeneric rescan = this.addKeyedButton(buttonX, y, "lmp.gui.setup.rescan",
                (button, mouse) -> this.rescan());
        rescan.setEnabled(this.regionId != null);
        y += ROW_HEIGHT + GAP;

        this.addKeyedButton(x + LABEL_WIDTH, y, "lmp.gui.setup.back", (button, mouse) -> this.closeGui(true));
    }

    private ButtonGeneric addKeyedButton(int x, int y, String translationKey, IButtonActionListener listener)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, translationKey);
        this.buttonsByKey.put(translationKey, button);
        this.addButton(button, listener);
        return button;
    }

    @Override
    public void removed()
    {
        ClientProgressStore.get().removeListener(this.storeListener);
        super.removed();
    }

    // ------------------------------------------------------------ 动作

    /** 把材料区设为该原理图的包围盒；没有尺寸时给出明确说法而不是静默无反应。 */
    public void applySchematicBounds()
    {
        if (this.schematicBounds == null)
        {
            this.message(Message.MessageType.WARNING, "lmp.gui.setup.no_bounds");
            return;
        }

        this.draft.setCorners(this.schematicBounds[0], this.schematicBounds[1]);
        this.refresh();
    }

    /** 把材料区设为 Litematica 的当前选区（多个子区域取并集）。 */
    public void applySelection()
    {
        if (!LmpProgressScreens.litematicaAvailable())
        {
            // 按钮在缺 Litematica 时是灰的，但这是公开方法，GameTest 与将来的入口都能直接调
            this.message(Message.MessageType.ERROR, "lmp.gui.status.needs_litematica");
            return;
        }

        if (!this.inPlacementDimension())
        {
            this.message(Message.MessageType.ERROR, "lmp.gui.setup.selection_other_dimension");
            return;
        }

        Optional<BlockPos[]> bounds = LmpSelectionBridge.currentBounds();

        if (bounds.isEmpty())
        {
            this.message(Message.MessageType.WARNING, "lmp.gui.setup.no_selection");
            return;
        }

        this.draft.setCorners(bounds.get()[0], bounds.get()[1]);
        this.refresh();
    }

    /**
     * 提交保存。
     *
     * @return 是否真的发出去了；本地校验没过或服务端不支持时为 {@code false}
     */
    public boolean save()
    {
        this.refresh();

        Optional<MaterialRegion.Rejection> rejection = this.draft.validate();

        if (rejection.isPresent())
        {
            this.message(Message.MessageType.ERROR, rejection.get().messageKey(), rejection.get().argsArray());
            return false;
        }

        if (!LmpClientNetworking.canSaveRegion())
        {
            // 服务端装了本模组但不认这个 payload（旧版本）：必须说清楚，不能报「已提交」
            this.message(Message.MessageType.ERROR, "lmp.gui.status.server_too_old");
            return false;
        }

        BlockPos[] corners = this.draft.corners().orElseThrow();
        LmpClientNetworking.saveRegion(this.regionId, this.draft.trimmedName(), this.dimension,
                corners[0], corners[1], this.placementId);
        this.submitted = true;
        this.message(Message.MessageType.INFO, "lmp.gui.setup.submitted");
        return true;
    }

    /** 删除这个材料区；还没有区域时什么都不做。 */
    public void deleteRegion()
    {
        if (this.regionId == null)
        {
            return;
        }

        if (!LmpClientNetworking.canDeleteRegion())
        {
            this.message(Message.MessageType.ERROR, "lmp.gui.status.server_too_old");
            return;
        }

        LmpClientNetworking.deleteRegion(this.regionId);
        this.message(Message.MessageType.INFO, "lmp.gui.setup.submitted");
    }

    /** 打开进度界面；还没有区域时什么都不做。 */
    public void openProgress()
    {
        if (this.regionId != null)
        {
            LmpProgressScreens.open(this.regionId, this.draft.name());
        }
    }

    /** 强制重扫（仍受服务端按玩家限流）。 */
    public void rescan()
    {
        if (this.regionId == null)
        {
            return;
        }

        long revision = ClientProgressStore.get().progress(this.regionId)
                .map(MaterialProgress::revision)
                .orElse(0L);

        LmpClientNetworking.requestSnapshot(this.regionId, revision, true);
    }

    // ------------------------------------------------------------ 状态文本

    private String rangeText()
    {
        Optional<BlockPos[]> corners = this.draft.corners();

        if (corners.isEmpty())
        {
            return StringUtils.translate("lmp.gui.setup.range.unset");
        }

        BlockPos min = corners.get()[0];
        BlockPos max = corners.get()[1];

        return StringUtils.translate("lmp.gui.setup.range.set",
                min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    private String sizeText()
    {
        if (!this.draft.rangeSet())
        {
            return "";
        }

        return StringUtils.translate("lmp.gui.setup.range.size",
                this.draft.volume(), this.draft.chunkCount(), MaterialRegion.MAX_VOLUME, MaterialRegion.MAX_CHUNKS);
    }

    /** 当前区域的扫描状态；没有区域或没有数据时给出明确说法，而不是空白。 */
    private String statusText()
    {
        if (this.regionId == null)
        {
            return StringUtils.translate("lmp.gui.setup.status.none");
        }

        ClientProgressStore store = ClientProgressStore.get();
        ClientProgressStore.Status status = store.status(this.regionId).orElse(null);

        if (status != null && status.phase() == ClientProgressStore.Phase.SCANNING)
        {
            return StringUtils.translate("lmp.gui.status.scanning",
                    String.format(java.util.Locale.ROOT, "%.0f", status.fraction() * 100.0D), status.chunksDone(), status.chunksTotal());
        }

        if (status != null && status.phase() == ClientProgressStore.Phase.FINALIZING)
        {
            return StringUtils.translate("lmp.gui.status.finalizing");
        }

        MaterialProgress progress = store.progress(this.regionId).orElse(null);

        if (progress == null)
        {
            return StringUtils.translate("lmp.gui.status.no_data");
        }

        String scanned = StringUtils.translate("lmp.gui.setup.status.coverage", progress.coveragePermille());

        if (!progress.hasRequired())
        {
            return scanned + " · " + StringUtils.translate("lmp.gui.status.unknown_required");
        }

        return scanned + " · " + StringUtils.translate("lmp.gui.progress.types",
                String.format(java.util.Locale.ROOT, "%.1f", progress.typeProgress() * 100.0D),
                progress.completedTypes(), progress.totalTypes())
                + " · " + StringUtils.translate("lmp.gui.progress.items",
                String.format(java.util.Locale.ROOT, "%.1f", progress.itemProgress() * 100.0D),
                progress.cappedPresent(), progress.totalRequired());
    }

    // ------------------------------------------------------------ 状态同步

    /** 玩家当前所在维度是否就是这个放置的维度；不是的话「用当前选区」会取到别处。 */
    private boolean inPlacementDimension()
    {
        MinecraftClient client = MinecraftClient.getInstance();

        return client.world != null && client.world.getRegistryKey().getValue().equals(this.dimension);
    }

    /** 打开界面时接住已有的区域（玩家是来改它的，不是来新建的）。 */
    private void adoptStoredRegion()
    {
        LmpPayloads.RegionInfo existing = ClientProgressStore.get().regionForPlacement(this.placementId).orElse(null);

        if (existing == null)
        {
            return;
        }

        this.regionId = existing.id();
        this.draft.setName(existing.name());
        this.draft.setCorners(existing.pos1(), existing.pos2());
    }

    /**
     * 区域清单变了：接住刚保存成功的新区域，或者放掉已经被别处删掉的区域。
     *
     * <p>不能无条件重读：玩家可能正在改名，重读会把输入框里的字擦掉。
     */
    private void onStoreChanged()
    {
        ClientProgressStore store = ClientProgressStore.get();

        if (this.regionId == null)
        {
            LmpPayloads.RegionInfo created = store.regionForPlacement(this.placementId).orElse(null);

            if (created != null)
            {
                // 接住的条件：要么这是自己刚提交的那次保存（否则刚建好的区域会接不住，
                // 「删除 / 打开进度」永远灰着），要么玩家还没动过草稿（别人先建好了，看一眼也无妨）。
                // 玩家正在编辑时直接覆盖会把名字与刚取好的范围擦掉——那种情况下服务端会以
                // 「这个原理图已被占用」明确拒绝，比悄悄改掉输入诚实。
                boolean adopt = this.submitted || this.draftUntouched();
                this.submitted = false;

                if (adopt)
                {
                    this.regionId = created.id();
                    this.draft.setName(created.name());
                    this.draft.setCorners(created.pos1(), created.pos2());
                    this.refresh();
                }
            }

            return;
        }

        if (store.regionById(this.regionId).isEmpty())
        {
            this.regionId = null;
            this.lastPhase = null;
            this.refresh();
            return;
        }

        // 只在扫描阶段变化时重建界面：快照与状态每 10 tick 就来一次，无条件重建会不停把输入框的焦点抢走。
        // 阶段变化足以让「正在扫描… / 覆盖率」不至于永远停在打开界面时那一刻。
        ClientProgressStore.Phase phase = store.status(this.regionId)
                .map(ClientProgressStore.Status::phase)
                .orElse(null);

        if (phase != this.lastPhase)
        {
            this.lastPhase = phase;
            this.refresh();
        }
    }

    /** 玩家是否还没动过草稿（名字没改、范围也没取）。 */
    private boolean draftUntouched()
    {
        return this.draft.trimmedName().equals(this.initialName) && !this.draft.rangeSet();
    }

    /** 把输入框里的名字收回草稿，然后重建界面（按钮的可用状态跟着范围与区域走）。 */
    private void refresh()
    {
        if (this.nameField != null)
        {
            this.draft.setName(this.nameField.getText());
        }

        this.initGui();
    }

    private void message(Message.MessageType type, String key, Object... args)
    {
        this.addMessage(type, key, args);
    }

    // ------------------------------------------------------------ 监听器（只做转发）

    private class NameListener implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric field)
        {
            LmpGuiPlacementSettings.this.draft.setName(field.getText());
            return false;
        }
    }
}

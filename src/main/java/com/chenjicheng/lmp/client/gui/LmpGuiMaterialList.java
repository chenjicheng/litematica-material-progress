package com.chenjicheng.lmp.client.gui;

import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.malilib.util.StringUtils;

import com.chenjicheng.lmp.client.ClientProgressStore;
import com.chenjicheng.lmp.material.MaterialProgress;

/**
 * Litematica 材料清单界面的薄子类，负责两件上游不管的事：
 *
 * <ol>
 *   <li><b>跟随快照更新</b>：订阅客户端缓存，快照到达时重建行并让上游刷新控件。</li>
 *   <li><b>把「种类进度」摆到看得见的地方</b>：上游标题栏下面那行汇总只显示数量维度
 *       （Total / Done% / Missing%），而约束要求两个维度都要显示，于是种类进度挂在标题上。</li>
 * </ol>
 *
 * <p>上游构造函数会覆盖 completionListener 并调用 {@code DataManager.setMaterialList}（仅当为空），
 * 这是复用它的既定代价：本模组不依赖材料清单 HUD 的任何状态。
 */
public class LmpGuiMaterialList extends GuiMaterialList
{
    private final LmpMaterialList list;
    private final Runnable storeListener = this::onStoreChanged;

    public LmpGuiMaterialList(LmpMaterialList list)
    {
        super(list);
        this.list = list;
        ClientProgressStore.get().addListener(this.storeListener);
    }

    /** 本界面正在展示的材料清单；供诊断与客户端 GameTest 断言使用。 */
    public LmpMaterialList materialList()
    {
        return this.list;
    }

    private void onStoreChanged()
    {
        this.list.apply(this.list.progress());
    }

    @Override
    public void removed()
    {
        ClientProgressStore.get().removeListener(this.storeListener);
        super.removed();
    }

    @Override
    public String getTitleString()
    {
        return super.getTitleString() + this.statusSuffix();
    }

    /** 标题后缀：扫描中显示进度，否则显示两个维度的完成度。 */
    private String statusSuffix()
    {
        ClientProgressStore store = ClientProgressStore.get();
        ClientProgressStore.Status status = store.status(this.list.regionId()).orElse(null);

        if (status != null && status.phase() == ClientProgressStore.Phase.FINALIZING)
        {
            // 读原理图时区块进度是 0/0，用「扫描中 0%」表述会像是卡住了
            return " | " + StringUtils.translate("lmp.gui.status.finalizing");
        }

        if (status != null && status.phase() == ClientProgressStore.Phase.SCANNING)
        {
            return " | " + StringUtils.translate("lmp.gui.status.scanning",
                    String.format("%.0f", status.fraction() * 100.0D),
                    status.chunksDone(), status.chunksTotal());
        }

        MaterialProgress progress = this.list.progress();

        if (progress == null)
        {
            return " | " + StringUtils.translate("lmp.gui.status.no_data");
        }

        if (!progress.hasRequired())
        {
            return " | " + StringUtils.translate("lmp.gui.status.unknown_required");
        }

        String types = StringUtils.translate("lmp.gui.progress.types",
                String.format("%.1f", progress.typeProgress() * 100.0D),
                progress.completedTypes(), progress.totalTypes());
        String items = StringUtils.translate("lmp.gui.progress.items",
                String.format("%.1f", progress.itemProgress() * 100.0D),
                progress.cappedPresent(), progress.totalRequired());

        // 服务端按全量原理图统计，不看玩家在 Litematica 里禁用了哪些子区域。
        // 不标出来的话，玩家拿自己的材料清单一对，数字对不上就会当成缺陷报过来。
        String note = " | " + StringUtils.translate("lmp.gui.status.full_schematic");

        if (progress.status() == MaterialProgress.Status.PARTIAL)
        {
            note += " · " + StringUtils.translate("lmp.gui.status.partial", progress.coveragePermille());
        }

        return " | " + types + " | " + items + note;
    }
}

package com.chenjicheng.lmp.region;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.material.UnknownRequiredMaterialSource;
import com.chenjicheng.lmp.scan.ScanScheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区域编辑的语义测试。
 *
 * <p>这一层是「界面保存」与「控制台命令」共用的服务端真相，所以每条拒绝理由都必须有对应的 i18n 键
 * 与参数个数——测试同时锁住「拒绝」和「拒绝时不许改动存储」两件事。
 */
class RegionEditorTest
{
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");

    @TempDir
    Path dir;

    private RegionStore store;
    private RegionEditor editor;
    private UUID actor;

    @BeforeEach
    void setUp()
    {
        this.store = RegionStore.load(RegionStore.fileFor(this.dir));
        this.editor = new RegionEditor(this.store, new ScanScheduler(this.store, UnknownRequiredMaterialSource.INSTANCE));
        this.actor = UUID.randomUUID();
    }

    private RegionEditor.Result save(UUID id, String name, BlockPos a, BlockPos b, UUID placement)
    {
        return this.editor.save(id, name, OVERWORLD, a, b, placement, this.actor);
    }

    private MaterialRegion seed(String name, UUID placement)
    {
        return this.store.put(new MaterialRegion(UUID.randomUUID(), name, OVERWORLD,
                p(0, 60, 0), p(15, 79, 15), placement, this.actor, 1234L, 99L, 7L, 1000));
    }

    private static BlockPos p(int x, int y, int z)
    {
        return new BlockPos(x, y, z);
    }

    // ------------------------------------------------------------ 新建

    @Test
    void createsRegionBoundToThePlacementAndReturnsItsSize()
    {
        UUID placement = UUID.randomUUID();
        RegionEditor.Result result = this.save(null, "主基地仓库", p(0, 60, 0), p(15, 79, 15), placement);

        assertTrue(result.ok(), "合法区域应当保存成功：" + result.messageKey());
        assertEquals(1, this.store.all().size());

        MaterialRegion stored = this.store.all().iterator().next();
        assertEquals("主基地仓库", stored.name());
        assertEquals(OVERWORLD, stored.dimension());
        assertEquals(p(0, 60, 0), stored.pos1());
        assertEquals(p(15, 79, 15), stored.pos2());
        assertEquals(placement, stored.placementId(), "界面保存必须顺手绑定 placement，否则玩家还得再绑一次");
        assertEquals(this.actor, stored.owner());
        assertEquals(1L, stored.revision(), "首次保存的版本号应当是 1");

        // 消息参数个数必须与 lmp.msg.region.created 的占位符一致：(名称, 体积, 区块数)
        assertEquals("lmp.msg.region.created", result.messageKey());
        assertEquals(3, result.args().size(), "消息参数个数必须与翻译占位符一致：" + result.args());
        assertEquals(16L * 20L * 16L, result.args().get(1));
        assertEquals(1L, result.args().get(2));
    }

    @Test
    void normalizesCornersSoThePlayerMayPickThemInAnyOrder()
    {
        this.save(null, "仓库", p(15, 79, 15), p(0, 60, 0), null);

        MaterialRegion stored = this.store.all().iterator().next();
        assertEquals(p(0, 60, 0), stored.pos1());
        assertEquals(p(15, 79, 15), stored.pos2());
    }

    // ------------------------------------------------------------ 更新

    @Test
    void updatesAnExistingRegionKeepingItsIdentity()
    {
        MaterialRegion created = this.seed("旧名", null);

        RegionEditor.Result result = this.save(created.id(), "新名", p(0, 60, 0), p(31, 79, 31), null);

        assertTrue(result.ok(), "更新应当成功：" + result.messageKey());
        assertEquals("lmp.msg.region.updated", result.messageKey());
        assertEquals(1, this.store.all().size(), "更新不得新增第二条区域");

        MaterialRegion stored = this.store.byId(created.id()).orElseThrow();
        assertEquals("新名", stored.name());
        assertEquals(p(31, 79, 31), stored.pos2());
        assertEquals(1234L, stored.createdAt(), "更新必须保留创建时间");
        assertEquals(this.actor, stored.owner());
        assertTrue(stored.revision() > created.revision(), "更新必须推进版本号，否则客户端会继续用旧快照");
    }

    @Test
    void rejectsAnUpdateOfAnUnknownRegion()
    {
        RegionEditor.Result result = this.save(UUID.randomUUID(), "无主", p(0, 60, 0), p(15, 79, 15), null);

        assertFalse(result.ok());
        assertEquals("lmp.msg.region.not_found", result.messageKey());
        assertTrue(this.store.all().isEmpty(), "被拒绝的编辑不得改动存储");
    }

    // ------------------------------------------------------------ 校验

    @Test
    void rejectsABlankName()
    {
        RegionEditor.Result result = this.save(null, "   ", p(0, 60, 0), p(15, 79, 15), null);

        assertFalse(result.ok());
        assertEquals("lmp.error.region.empty_name", result.messageKey());
        assertEquals(0, result.args().size(), "空名消息没有占位符");
        assertTrue(this.store.all().isEmpty());
    }

    @Test
    void rejectsAnOversizedRegionAndReportsBothNumbers()
    {
        RegionEditor.Result result = this.save(null, "太大",
                p(0, 0, 0), p((int) MaterialRegion.MAX_VOLUME, 1, 1), null);

        assertFalse(result.ok());
        assertEquals("lmp.error.region.too_large", result.messageKey());
        assertEquals(2, result.args().size(), "体积超限消息需要 (实际体积, 上限)");
        assertEquals(MaterialRegion.MAX_VOLUME, result.args().get(1));
        assertTrue(this.store.all().isEmpty());
    }

    @Test
    void rejectsARegionSpanningTooManyChunks()
    {
        // 区块上限 256：33×33 个区块 = 528×528 格，体积仍在 100 万以内，但区块数超限
        RegionEditor.Result result = this.save(null, "跨太多区块", p(0, 0, 0), p(527, 1, 527), null);

        assertFalse(result.ok());
        assertEquals("lmp.error.region.too_many_chunks", result.messageKey());
        assertEquals(1, result.args().size(), "区块超限消息只需要区块数");
        assertTrue(this.store.all().isEmpty());
    }

    @Test
    void rejectsAnOverlongName()
    {
        String tooLong = "仓".repeat(MaterialRegion.MAX_NAME_LENGTH + 1);

        RegionEditor.Result result = this.save(null, tooLong, p(0, 60, 0), p(15, 79, 15), null);

        assertFalse(result.ok());
        assertEquals("lmp.error.region.name_too_long", result.messageKey());
        assertEquals(1, result.args().size(), "名字过长消息需要上限值");
        assertEquals(MaterialRegion.MAX_NAME_LENGTH, result.args().get(0));
        assertTrue(this.store.all().isEmpty(), "被拒绝的编辑不得改动存储");
    }

    @Test
    void rejectsADuplicateNameIgnoringCase()
    {
        this.save(null, "Main Base", p(0, 60, 0), p(15, 79, 15), null);

        RegionEditor.Result result = this.save(null, "main base", p(100, 60, 100), p(115, 79, 115), null);

        assertFalse(result.ok());
        assertEquals("lmp.msg.region.exists", result.messageKey());
        assertEquals(1, this.store.all().size());
    }

    @Test
    void allowsARegionToKeepItsOwnNameWhenUpdating()
    {
        MaterialRegion created = this.seed("仓库", null);

        RegionEditor.Result result = this.save(created.id(), "仓库", p(0, 60, 0), p(31, 79, 31), null);

        assertTrue(result.ok(), "改名成自己不算重名：" + result.messageKey());
    }

    @Test
    void updatingWithoutAPlacementKeepsTheExistingBinding()
    {
        UUID placement = UUID.randomUUID();
        RegionEditor.Result created = this.save(null, "仓库", p(0, 60, 0), p(15, 79, 15), placement);
        assertTrue(created.ok(), created.messageKey());
        UUID id = this.store.all().iterator().next().id();

        // 控制台的 /lmp region create 只写字面参数，没提 placement
        RegionEditor.Result updated = this.save(id, "仓库", p(0, 60, 0), p(31, 79, 31), null);

        assertTrue(updated.ok(), updated.messageKey());
        assertEquals(placement, this.store.byId(id).orElseThrow().placementId(),
                "更新时没提 placement 不等于解绑：静默解绑是用户没要求的破坏");
    }

    // ------------------------------------------------------------ 绑定唯一性

    @Test
    void rejectsAPlacementAlreadyOwnedByAnotherRegion()
    {
        UUID placement = UUID.randomUUID();
        this.save(null, "甲", p(0, 60, 0), p(15, 79, 15), placement);

        RegionEditor.Result result = this.save(null, "乙", p(100, 60, 100), p(115, 79, 115), placement);

        assertFalse(result.ok());
        assertEquals("lmp.msg.region.placement_taken", result.messageKey());
        assertEquals(2, result.args().size(), "需要 (冲突区域名, placementId)");
        assertEquals(1, this.store.all().size(), "一个 placement 只能有一个材料区");
    }

    @Test
    void allowsARegionToKeepItsOwnPlacementWhenUpdating()
    {
        UUID placement = UUID.randomUUID();
        RegionEditor.Result created = this.save(null, "甲", p(0, 60, 0), p(15, 79, 15), placement);
        assertTrue(created.ok(), created.messageKey());
        UUID id = this.store.all().iterator().next().id();

        RegionEditor.Result result = this.save(id, "甲", p(0, 60, 0), p(31, 79, 31), placement);

        assertTrue(result.ok(), "自己绑过的 placement 不算冲突：" + result.messageKey());
    }

    // ------------------------------------------------------------ 按名字保存（控制台入口）

    @Test
    void saveByNameCreatesThenUpdatesTheSameRegion()
    {
        RegionEditor.Result created = this.editor.saveByName("仓库", OVERWORLD,
                p(0, 60, 0), p(15, 79, 15), null, this.actor);
        assertTrue(created.ok(), created.messageKey());
        assertEquals("lmp.msg.region.created", created.messageKey());

        RegionEditor.Result updated = this.editor.saveByName("仓库", OVERWORLD,
                p(0, 60, 0), p(31, 79, 31), null, this.actor);

        assertTrue(updated.ok(), updated.messageKey());
        assertEquals("lmp.msg.region.updated", updated.messageKey());
        assertEquals(1, this.store.all().size(), "同名保存不得产生第二条区域");
        assertEquals(p(31, 79, 31), this.store.all().iterator().next().pos2());
    }

    // ------------------------------------------------------------ 删除

    @Test
    void deletesAnExistingRegion()
    {
        MaterialRegion created = this.seed("待删", null);

        RegionEditor.Result result = this.editor.delete(created.id());

        assertTrue(result.ok());
        assertEquals("lmp.msg.region.deleted", result.messageKey());
        assertEquals(1, result.args().size(), "删除消息需要区域名");
        assertEquals("待删", result.args().get(0));
        assertTrue(this.store.byId(created.id()).isEmpty());
    }

    @Test
    void rejectsDeletingAnUnknownRegion()
    {
        RegionEditor.Result result = this.editor.delete(UUID.randomUUID());

        assertFalse(result.ok());
        assertEquals("lmp.msg.region.not_found", result.messageKey());
    }

    // ------------------------------------------------------------ 重扫

    @Test
    void rescanRequestsAScanForTheRegion()
    {
        MaterialRegion created = this.seed("仓库", null);

        RegionEditor.Result result = this.editor.rescan(created.id(), this.actor);

        assertTrue(result.ok(), "重扫应当被接受：" + result.messageKey());
        assertEquals("lmp.msg.scan.started", result.messageKey());
        assertEquals(1, result.args().size());
    }

    @Test
    void rescanIsRateLimitedPerPlayer()
    {
        MaterialRegion created = this.seed("仓库", null);

        assertTrue(this.editor.rescan(created.id(), this.actor).ok());

        RegionEditor.Result second = this.editor.rescan(created.id(), this.actor);

        assertFalse(second.ok(), "同一玩家连续重扫必须被限流");
        assertEquals("lmp.msg.scan.rate_limited", second.messageKey());
    }

    @Test
    void rescanRejectsAnUnknownRegion()
    {
        RegionEditor.Result result = this.editor.rescan(UUID.randomUUID(), this.actor);

        assertFalse(result.ok());
        assertEquals("lmp.msg.region.not_found", result.messageKey());
    }

    // ------------------------------------------------------------ 落盘

    @Test
    void reportsPersistedEditsWithoutAWarning()
    {
        RegionEditor.Result result = this.save(null, "仓库", p(0, 60, 0), p(15, 79, 15), null);

        assertTrue(result.ok());
        assertNull(result.warningKey(), "写盘成功时不应有额外警告");
        assertTrue(Files.isRegularFile(RegionStore.fileFor(this.dir)), "区域定义必须落盘");
    }
}

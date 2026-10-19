package com.chenjicheng.lmp.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.material.UnknownRequiredMaterialSource;
import com.chenjicheng.lmp.region.MaterialRegion;
import com.chenjicheng.lmp.region.RegionStore;
import com.chenjicheng.lmp.scan.RegionScanner;
import com.chenjicheng.lmp.scan.ScanScheduler;

/**
 * GameTest：需要真实 {@code ServerWorld} / {@code MinecraftServer} 才能验证的两件事。
 *
 * <p>单元测试覆盖不到它们：计数语义可以在纯内存里测，但「扫描器真的从已加载区块里读到方块实体」
 * 和「取消后的扫描不会把刚删掉的区域写回存储」必须跑在真服务端上。
 *
 * <p>本类位于独立的 gametest 源集，不进入发布产物。
 */
public class LmpGameTest
{
    /** 驱动扫描直到结束；同时兜住「永远不结束」的实现缺陷，避免把测试挂死。 */
    private static void driveToCompletion(RegionScanner scanner)
    {
        for (int i = 0; i < 10_000; i++)
        {
            if (scanner.advance(System.nanoTime() + 50_000_000L))
            {
                return;
            }
        }

        throw new AssertionError("扫描在 10000 次推进后仍未结束");
    }

    private static void fill(ServerWorld world, BlockPos pos, int slot, ItemStack stack)
    {
        if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest)
        {
            chest.setStack(slot, stack);
            return;
        }

        throw new AssertionError("(" + pos.toShortString() + ") 处没有箱子方块实体");
    }

    private static long count(Map<Item, Long> counts, Item item)
    {
        return counts.getOrDefault(item, 0L);
    }

    private static MaterialRegion region(TestContext context, String name)
    {
        ServerWorld world = context.getWorld();

        return new MaterialRegion(UUID.randomUUID(), name, world.getRegistryKey().getValue(),
                context.getAbsolutePos(new BlockPos(0, 0, 0)),
                context.getAbsolutePos(new BlockPos(3, 3, 3)),
                null, UUID.randomUUID(), 0L, 0L, 0L, 0);
    }

    @GameTest(maxTicks = 200)
    public void countsOnlyContainersInsideTheRegion(TestContext context)
    {
        final ServerWorld world = context.getWorld();

        context.setBlockState(1, 1, 1, Blocks.CHEST);
        context.setBlockState(2, 1, 1, Blocks.CHEST);
        context.setBlockState(6, 1, 6, Blocks.CHEST);

        fill(world, context.getAbsolutePos(new BlockPos(1, 1, 1)), 0, new ItemStack(Items.DIAMOND, 5));
        fill(world, context.getAbsolutePos(new BlockPos(2, 1, 1)), 0, new ItemStack(Items.DIAMOND, 3));
        fill(world, context.getAbsolutePos(new BlockPos(6, 1, 6)), 0, new ItemStack(Items.DIAMOND, 100));

        final RegionScanner scanner = new RegionScanner(world, region(context, "gametest"));
        driveToCompletion(scanner);

        context.assertTrue(scanner.chunksDone() == scanner.chunksTotal(),
                Text.literal("所有区块都应已扫描：done=" + scanner.chunksDone() + " total=" + scanner.chunksTotal()));
        context.assertTrue(count(scanner.counts(), Items.DIAMOND) == 8L,
                Text.literal("区域内的钻石应为 8（5+3），实际 " + count(scanner.counts(), Items.DIAMOND)));
        context.assertTrue(scanner.resultStatus() == MaterialProgress.Status.COMPLETE,
                Text.literal("全部区块已加载时应为 COMPLETE，实际 " + scanner.resultStatus()));
        context.assertTrue(scanner.containersScanned() == 2,
                Text.literal("只应统计区域内的 2 个箱子，实际 " + scanner.containersScanned()));

        context.complete();
    }

    /**
     * 复现「扫描进行中执行 /lmp region delete」：区域必须保持删除状态，不能被收尾的扫描写回来。
     *
     * <p>用监听者在扫描刚启动（状态已发布、尚未推进）时取消并删除，这样无论区域多小都能稳定命中
     * 那条路径——小区域会在一 tick 内扫完，靠时序去卡是测不稳的。
     */
    @GameTest(maxTicks = 200)
    public void cancelledScanDoesNotResurrectDeletedRegion(TestContext context) throws Exception
    {
        final ServerWorld world = context.getWorld();
        final MinecraftServer server = world.getServer();
        final Path dir = Files.createTempDirectory("lmp-gametest");
        final RegionStore store = RegionStore.load(RegionStore.fileFor(dir));

        final MaterialRegion stored = store.put(region(context, "doomed"));
        final ScanScheduler scheduler = new ScanScheduler(store, UnknownRequiredMaterialSource.INSTANCE);

        // 监听者扮演「扫描刚开始时玩家执行了删除」：取消该区域的扫描，并把区域删掉
        scheduler.addListener(new ScanScheduler.Listener()
        {
            @Override
            public void onSnapshotUpdated(UUID regionId, MaterialProgress progress)
            {
            }

            @Override
            public void onStatusUpdated(UUID regionId, ScanScheduler.ScanStatus status)
            {
                if (regionId.equals(stored.id()) && status.phase() == ScanScheduler.Phase.SCANNING)
                {
                    scheduler.cancelRegion(stored.id());
                    store.remove(stored.id());
                }
            }
        });

        scheduler.requestScan(stored, null);
        scheduler.tick(server);

        context.assertTrue(store.byId(stored.id()).isEmpty(),
                Text.literal("被取消的扫描不得把已删除的区域写回存储"));
        context.assertTrue(scheduler.snapshot(stored.id()).isEmpty(),
                Text.literal("被取消的扫描不得发布快照"));
        context.assertTrue(!scheduler.isScanning(stored.id()), Text.literal("取消后不应仍在扫描"));

        context.complete();
    }

    /**
     * 取消一个区域的扫描，不得顺带丢掉另一个区域排队中的请求。
     *
     * <p>旧实现里 cancelRegion 借道 cancel()，而 cancel() 会把 queuedRegionId 一并清空——
     * 删掉 A 的时候，B 的排队请求就没了，而且没有任何提示。用监听者在 A 的扫描状态刚发布时
     * 排上 B 并取消 A，这样无论区域多小都能稳定命中这条路径。
     */
    @GameTest(maxTicks = 200)
    public void cancellingOneRegionKeepsAnotherQueuedRequest(TestContext context) throws Exception
    {
        final ServerWorld world = context.getWorld();
        final MinecraftServer server = world.getServer();
        final Path dir = Files.createTempDirectory("lmp-gametest-queue");
        final RegionStore store = RegionStore.load(RegionStore.fileFor(dir));
        final MaterialRegion first = store.put(region(context, "first"));
        final MaterialRegion second = store.put(region(context, "second"));
        final ScanScheduler scheduler = new ScanScheduler(store, UnknownRequiredMaterialSource.INSTANCE);
        final boolean[] handled = { false };

        scheduler.addListener(new ScanScheduler.Listener()
        {
            @Override
            public void onSnapshotUpdated(UUID regionId, MaterialProgress progress)
            {
            }

            @Override
            public void onStatusUpdated(UUID regionId, ScanScheduler.ScanStatus status)
            {
                if (!handled[0] && regionId.equals(first.id()) && status.phase() == ScanScheduler.Phase.SCANNING)
                {
                    handled[0] = true;
                    scheduler.requestScan(second, null);
                    scheduler.cancelRegion(first.id());
                }
            }
        });

        scheduler.requestScan(first, null);
        scheduler.tick(server);

        // 服务器忙的时候一 tick 可能整片跳过（自适应预算），所以推进到「第二个区域确实被扫过」为止，
        // 而不是假设两 tick 就够
        for (int i = 0; i < 20 && !(scheduler.isScanning(second.id()) || scheduler.snapshot(second.id()).isPresent()); i++)
        {
            scheduler.tick(server);
        }

        context.assertTrue(scheduler.isScanning(second.id()) || scheduler.snapshot(second.id()).isPresent(),
                Text.literal("取消第一个区域不得把第二个区域的排队请求一起丢掉"));

        context.complete();
    }

    /**
     * 复现「扫描进行中玩家改了这个区域」（例如在设置界面里绑定共享原理图）：收尾不得把改动覆盖回去。
     *
     * <p>旧实现收尾时把「扫描开始时」捕获的那条记录<b>整条</b>写回存储，于是绑定被静默回滚，
     * 而且回滚结果还会在服务器停止时落盘——用户编辑就此永久丢失。
     *
     * <p>用监听者在扫描刚启动（状态已发布、尚未推进）时改区域，这样无论区域多小都能稳定命中那条路径。
     */
    @GameTest(maxTicks = 200)
    public void editsMadeDuringAScanSurviveTheScan(TestContext context) throws Exception
    {
        final ServerWorld world = context.getWorld();
        final MinecraftServer server = world.getServer();
        final Path dir = Files.createTempDirectory("lmp-gametest-edit");
        final RegionStore store = RegionStore.load(RegionStore.fileFor(dir));
        final MaterialRegion stored = store.put(region(context, "edited"));
        final UUID placement = UUID.randomUUID();
        final ScanScheduler scheduler = new ScanScheduler(store, UnknownRequiredMaterialSource.INSTANCE);

        scheduler.addListener(new ScanScheduler.Listener()
        {
            @Override
            public void onSnapshotUpdated(UUID regionId, MaterialProgress progress)
            {
            }

            @Override
            public void onStatusUpdated(UUID regionId, ScanScheduler.ScanStatus status)
            {
                if (regionId.equals(stored.id()) && status.phase() == ScanScheduler.Phase.SCANNING)
                {
                    // 玩家在扫描期间绑定了共享原理图
                    store.put(store.byId(stored.id()).orElseThrow().withPlacement(placement));
                }
            }
        });

        scheduler.requestScan(stored, null);

        for (int i = 0; i < 100 && (i == 0 || scheduler.isScanning(stored.id())); i++)
        {
            scheduler.tick(server);
        }

        MaterialRegion after = store.byId(stored.id()).orElseThrow();
        context.assertTrue(placement.equals(after.placementId()),
                Text.literal("扫描收尾不得回滚扫描期间的绑定，实际 " + after.placementId()));
        context.assertTrue(after.lastScanAt() > 0L,
                Text.literal("扫描结果本身仍然要写回（lastScanAt=" + after.lastScanAt() + "）"));

        context.complete();
    }

    /** 对照组：没被取消的扫描必须照常把结果写回存储，否则上面那条断言可能只是因为「什么都没发生」。 */
    @GameTest(maxTicks = 200)
    public void completedScanPersistsItsResult(TestContext context) throws Exception
    {
        final ServerWorld world = context.getWorld();
        final MinecraftServer server = world.getServer();
        final Path dir = Files.createTempDirectory("lmp-gametest-ok");
        final RegionStore store = RegionStore.load(RegionStore.fileFor(dir));

        final MaterialRegion stored = store.put(region(context, "kept"));
        final ScanScheduler scheduler = new ScanScheduler(store, UnknownRequiredMaterialSource.INSTANCE);

        scheduler.requestScan(stored, null);
        scheduler.tick(server);

        context.assertTrue(store.byId(stored.id()).isPresent(), Text.literal("正常完成的扫描不应把区域删掉"));
        context.assertTrue(scheduler.snapshot(stored.id()).isPresent(), Text.literal("正常完成的扫描应当发布快照"));

        context.complete();
    }
}

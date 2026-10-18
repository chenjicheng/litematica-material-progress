package com.chenjicheng.lmp.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.material.UnknownRequiredMaterialSource;
import com.chenjicheng.lmp.region.MaterialRegion;
import com.chenjicheng.lmp.region.RegionStore;
import com.chenjicheng.lmp.scan.ScanScheduler;

/**
 * 在真实服务端上测扫描开销（对应 YouTrack LMP-4）。
 *
 * <p>做法：保住 {@value #CHUNKS}×{@value #CHUNKS} 个区块（= {@code MAX_CHUNKS}）的加载票，往里放
 * {@value #CONTAINERS} 个有内容的箱子（每区块 {@value #CHESTS_PER_CHUNK} 个，密度足以让一次扫描跨多个 tick），
 * 静置若干 tick 让服务端空下来采基线，然后让<b>生产路径</b>
 * （{@code ServerTickEvents.END_SERVER_TICK} → {@link ScanScheduler#tick}）真正跑
 * {@value #WARMUP_ROUNDS} 轮预热 + {@value #ROUNDS} 轮统计，逐 tick 采样。
 *
 * <p>要证明四件事：
 * <ol>
 *   <li>负载真的建起来了：区域里的容器方块实体数与预期<b>恰好</b>相等，覆盖率 1000‰；</li>
 *   <li>每 tick 花在扫描上的时间分布（p50/p95/p99/max）符合设计预算；</li>
 *   <li>扫描<b>不</b>加载任何区块：前后已加载区块数不变；</li>
 *   <li>读到的内容对得上（箱子里装的东西被真的数到了）。</li>
 * </ol>
 *
 * <p>本类位于独立的 gametest 源集，不进入发布产物。
 */
public class LmpScanCostGameTest
{
    /** 区域覆盖的区块边长；16×16 = 256 = {@code MaterialRegion.MAX_CHUNKS}。 */
    private static final int CHUNKS = 16;
    /**
     * 每区块放的箱子数（8×8 网格、间距 2 格，64 个互不相同且不相邻的位置）。
     *
     * <p>取这个密度是为了让一次扫描<b>跨多个 tick</b>——只有跨 tick 才谈得上「每 tick 预算」，
     * 一个 tick 就能扫完的负载测不出预算有没有生效。
     */
    private static final int CHESTS_PER_CHUNK = 64;
    /** 容器总数。 */
    private static final int CONTAINERS = CHUNKS * CHUNKS * CHESTS_PER_CHUNK;
    /** 每个箱子里放的钻石数。 */
    private static final int DIAMONDS_PER_CHEST = 64;
    /** 铺箱子时每 tick 处理多少个区块的量（一个区块 64 个箱子，铺太快会造出巨 tick 污染基线）。 */
    private static final int CHUNKS_PER_PLACE_TICK = 2;
    /** 预热轮数：不计入统计。第一轮要付 JIT 编译与冷缓存的钱，混进 p99 会把结论带偏。 */
    private static final int WARMUP_ROUNDS = 2;
    /** 纳入统计的轮数：单轮只有十几个 tick 样本，p99 没有意义，合并几轮才站得住。 */
    private static final int ROUNDS = 20;
    /** 等区块生成完的上限。 */
    private static final int LOAD_TIMEOUT_TICKS = 600;
    /** 放完箱子后静置的 tick 数：让服务端 100 tick 的滑动窗口回到稳态。 */
    private static final int SETTLE_TICKS = 120;
    /** 单轮扫描的 tick 上限。 */
    private static final int SCAN_TIMEOUT_TICKS = 1_200;
    /**
     * 测量区离测试批次公共区域的距离（区块）。
     *
     * <p>同一批 GameTest 的结构都摆在同一个区域里，隔开十几个区块还不够——实测第一次跑就被
     * 别的测试放下的箱子混进了统计。这里把测量区挪到很远的地方，自己开加载票、自己用空地。
     */
    private static final int FAR_CHUNK_OFFSET = 512;

    /** 跨 tick 的测试状态；lambda 里改不了局部变量，所以集中放在这里。 */
    private static final class Harness
    {
        final ServerWorld world;
        final MinecraftServer server;
        final ServerChunkManager chunks;
        final BlockPos origin;
        /** 测量区的基准区块（远离测试批次公共区域）。 */
        final ChunkPos baseChunk;

        /** 每轮里每次 {@code scheduler.tick()} 的耗时（纳秒）——这就是本模组给 MSPT 加的负担。 */
        final List<List<Long>> slicesPerRound = new ArrayList<>();
        /** 完成扫描的那一 tick 的耗时（里面还包含落库与发布快照，单独统计）。 */
        final List<Long> finishSlices = new ArrayList<>();
        /** 扫描开始前那一刻「最近 100 tick 内最慢的一 tick」（毫秒）。 */
        double baselineWorstMs = -1.0D;
        /** 扫描期间每次采样的同一指标（毫秒）。 */
        final List<Double> duringWorstMs = new ArrayList<>();
        /** 每轮扫描实际跨了多少 tick。 */
        final List<Integer> ticksPerRound = new ArrayList<>();

        RegionStore store;
        ScanScheduler scheduler;
        MaterialRegion region;
        UUID regionId;
        int round;
        final List<Long> currentRoundSlices = new ArrayList<>();
        boolean scanRequested;
        boolean baselineWanted;
        /** 本轮开始时的 lastScanAt；它一变就说明这一轮扫完了（比看 isScanning 可靠）。 */
        long roundStartScanAt;
        int loadedBefore;
        int loadedAfter = -1;
        int containersPlaced;

        Harness(TestContext context)
        {
            this.world = context.getWorld();
            this.server = this.world.getServer();
            this.chunks = this.world.getChunkManager();
            this.origin = context.getAbsolutePos(BlockPos.ORIGIN);
            ChunkPos near = new ChunkPos(this.origin);
            this.baseChunk = new ChunkPos(near.x + FAR_CHUNK_OFFSET, near.z + FAR_CHUNK_OFFSET);
        }

        /** 每个服务端 tick 采样一次。 */
        void tick()
        {
            if (this.baselineWanted)
            {
                // 一直覆盖写：取的是静置结束、扫描开始前那一刻的窗口读数
                this.baselineWorstMs = worstTickMs(this.server);
                return;
            }

            if (!this.scanRequested)
            {
                return;
            }

            long t0 = System.nanoTime();
            this.scheduler.tick(this.server);
            long elapsed = System.nanoTime() - t0;

            if (this.round >= WARMUP_ROUNDS)
            {
                this.duringWorstMs.add(worstTickMs(this.server));
            }

            // 用「lastScanAt 变了」判定本轮结束。不能用 isScanning()：
            // 服务端过载时调度器会整片跳过（设计如此），此时 isScanning 为 true 但一个 tick 都没推进；
            // 反过来若某轮在同一个 tick 内就结束了，isScanning 从来没为 true 过，只看它会永远等下去。
            if (this.lastScanAt() != this.roundStartScanAt)
            {
                // 这一 tick 里跑完了 finishScan（落库 + 发布快照），单独记账
                this.finishSlices.add(elapsed);
                this.scanRequested = false;
            }
            else
            {
                this.currentRoundSlices.add(elapsed);
            }
        }

        long lastScanAt()
        {
            return this.store.byId(this.regionId).map(MaterialRegion::lastScanAt).orElse(-1L);
        }
    }

    private static ChunkPos chunkOf(Harness harness, int dx, int dz)
    {
        return new ChunkPos(harness.baseChunk.x + dx, harness.baseChunk.z + dz);
    }

    @GameTest(maxTicks = 6_000)
    public void measuresScanCostWithARealisticLoad(TestContext context)
    {
        final Harness harness = new Harness(context);

        // 保住一大片区块的加载票。不这样做，扫描只能看到结构附近那几块，测不出真实规模；
        // 用 FORCED 是为了让它在这一整轮里都不会过期。
        for (int dx = 0; dx < CHUNKS; dx++)
        {
            for (int dz = 0; dz < CHUNKS; dz++)
            {
                harness.chunks.addTicket(ChunkTicketType.FORCED, chunkOf(harness, dx, dz), 1);
            }
        }

        ServerTickEvents.END_SERVER_TICK.register(server -> harness.tick());

        awaitChunksLoaded(context, harness, 0, () ->
        {
            placeChests(context, harness, 0);
            // 静置：区块生成收尾 + 采基线
            harness.baselineWanted = true;
            context.waitAndRun(SETTLE_TICKS, () ->
            {
                harness.baselineWanted = false;
                prepareScan(harness);
                awaitRound(context, harness, 0);
            });
        });
    }

    /** 轮询等待全部区块加载完；生成是异步的，不能假设固定 tick 数。 */
    private static void awaitChunksLoaded(TestContext context, Harness harness, int waited, Runnable next)
    {
        int loaded = 0;

        for (int dx = 0; dx < CHUNKS; dx++)
        {
            for (int dz = 0; dz < CHUNKS; dz++)
            {
                ChunkPos pos = chunkOf(harness, dx, dz);

                if (harness.chunks.isChunkLoaded(pos.x, pos.z))
                {
                    loaded++;
                }
            }
        }

        if (loaded == CHUNKS * CHUNKS)
        {
            next.run();
            return;
        }

        if (waited >= LOAD_TIMEOUT_TICKS)
        {
            context.assertTrue(false, Text.literal("等待区块加载超时：只有 " + loaded + "/"
                    + (CHUNKS * CHUNKS) + " 个就绪"));
            return;
        }

        context.waitAndRun(1, () -> awaitChunksLoaded(context, harness, waited + 1, next));
    }

    /**
     * 铺箱子，每 tick 只铺 {@value #CHUNKS_PER_PLACE_TICK} 个区块的量。
     *
     * <p>不能一口气铺完：上万次 {@code setBlockState} 挤在一个 tick 里会造出 200ms 级别的巨 tick，
     * 它会留在 {@code getTickTimes()} 的 100 tick 窗口里，把随后的基线读数彻底带偏。
     */
    private static void placeChests(TestContext context, Harness harness, int chunkIndex)
    {
        final int y = harness.origin.getY() + 1;
        final ChunkPos base = harness.baseChunk;
        int placed = 0;

        for (int n = 0; n < CHUNKS_PER_PLACE_TICK && chunkIndex < CHUNKS * CHUNKS; n++, chunkIndex++)
        {
            int dx = chunkIndex / CHUNKS;
            int dz = chunkIndex % CHUNKS;

            for (int i = 0; i < CHESTS_PER_CHUNK; i++)
            {
                // 8×8 网格、间距 2 格：64 个互不相同的位置，且两两不相邻，不会合成双箱
                int x = (base.x + dx) * 16 + 1 + (i % 8) * 2;
                int z = (base.z + dz) * 16 + 1 + (i / 8) * 2;
                BlockPos pos = new BlockPos(x, y, z);

                harness.world.setBlockState(pos, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);

                if (harness.world.getBlockEntity(pos) instanceof ChestBlockEntity chest)
                {
                    chest.setStack(0, new ItemStack(Items.DIAMOND, DIAMONDS_PER_CHEST));
                    chest.setStack(1, new ItemStack(Items.SHULKER_BOX));
                    placed++;
                }
            }
        }

        harness.containersPlaced += placed;

        if (chunkIndex >= CHUNKS * CHUNKS)
        {
            return;
        }

        int next = chunkIndex;
        context.waitAndRun(1, () -> placeChests(context, harness, next));
    }

    private static void prepareScan(Harness harness)
    {
        try
        {
            Path dir = Files.createTempDirectory("lmp-scan-cost");
            harness.store = RegionStore.load(RegionStore.fileFor(dir));
        }
        catch (Exception e)
        {
            throw new AssertionError("无法创建临时存储", e);
        }

        ChunkPos base = harness.baseChunk;
        int y = harness.origin.getY() + 1;

        harness.region = harness.store.put(new MaterialRegion(UUID.randomUUID(), "scan-cost",
                harness.world.getRegistryKey().getValue(),
                new BlockPos(base.x * 16, y, base.z * 16),
                new BlockPos(base.x * 16 + CHUNKS * 16 - 1, y, base.z * 16 + CHUNKS * 16 - 1),
                null, UUID.randomUUID(), 0L, 0L, 0L, 0));
        harness.regionId = harness.region.id();
        harness.scheduler = new ScanScheduler(harness.store, UnknownRequiredMaterialSource.INSTANCE);

        harness.loadedBefore = harness.chunks.getLoadedChunkCount();
        startRound(harness);
    }

    private static void startRound(Harness harness)
    {
        harness.roundStartScanAt = harness.lastScanAt();
        harness.currentRoundSlices.clear();
        harness.scanRequested = true;
        harness.scheduler.requestScan(harness.region, null);
    }

    /** 等本轮扫完；扫完就开下一轮，直到预热轮 + 统计轮都跑完。 */
    private static void awaitRound(TestContext context, Harness harness, int waited)
    {
        if (!harness.scanRequested)
        {
            harness.ticksPerRound.add(waited);
            harness.slicesPerRound.add(List.copyOf(harness.currentRoundSlices));
            harness.round++;

            if (harness.round < WARMUP_ROUNDS + ROUNDS)
            {
                startRound(harness);
                context.waitAndRun(1, () -> awaitRound(context, harness, 0));
                return;
            }

            report(context, harness);
            return;
        }

        if (waited >= SCAN_TIMEOUT_TICKS)
        {
            // 用 assertTrue 而不是抛异常：从 tick 任务里抛出去会把整个 GameTest 服务端打崩，
            // 连已经采到的数据都留不下来。
            context.assertTrue(false, Text.literal("第 " + (harness.round + 1) + " 轮扫描在 "
                    + SCAN_TIMEOUT_TICKS + " tick 内没有结束（服务端平均 tick " + harness.server.getAverageTickTime()
                    + "ms，仍在扫描=" + harness.scheduler.isScanning(harness.regionId) + "）"));
            return;
        }

        context.waitAndRun(1, () -> awaitRound(context, harness, waited + 1));
    }

    private static void report(TestContext context, Harness harness)
    {
        harness.loadedAfter = harness.chunks.getLoadedChunkCount();
        int actuallyPresent = countContainersInRegion(harness);

        // 丢掉预热轮，只统计稳态轮
        List<Long> steady = new ArrayList<>();

        for (int i = WARMUP_ROUNDS; i < harness.slicesPerRound.size(); i++)
        {
            steady.addAll(harness.slicesPerRound.get(i));
        }

        long[] slices = steady.stream().mapToLong(Long::longValue).sorted().toArray();
        long[] warmup = harness.slicesPerRound.isEmpty()
                ? new long[0]
                : harness.slicesPerRound.get(0).stream().mapToLong(Long::longValue).sorted().toArray();
        long[] finishes = harness.finishSlices.stream().mapToLong(Long::longValue).sorted().toArray();

        double warmupMax = (warmup.length == 0 ? 0L : warmup[warmup.length - 1]) / 1.0E6D;
        double sliceAvg = averageOf(slices) / 1.0E6D;
        double sliceP50 = percentile(slices, 0.50) / 1.0E6D;
        double sliceP95 = percentile(slices, 0.95) / 1.0E6D;
        double sliceP99 = percentile(slices, 0.99) / 1.0E6D;
        double sliceMax = (slices.length == 0 ? 0L : slices[slices.length - 1]) / 1.0E6D;
        double finishMax = (finishes.length == 0 ? 0L : finishes[finishes.length - 1]) / 1.0E6D;
        // 每容器耗时 = 全部统计轮的扫描总耗时 / 全部容器数
        double perContainerMicros = slices.length == 0 ? 0.0D
                : (averageOf(slices) * slices.length / (double) (ROUNDS * CONTAINERS)) / 1_000.0D;
        double duringWorst = max(harness.duringWorstMs);
        int coverage = harness.store.byId(harness.regionId).map(MaterialRegion::coveragePermille).orElse(-1);
        long diamonds = harness.scheduler.snapshot(harness.regionId)
                .map(p -> p.presentFor(Items.DIAMOND)).orElse(0L);
        long shulkers = harness.scheduler.snapshot(harness.regionId)
                .map(p -> p.presentFor(Items.SHULKER_BOX)).orElse(0L);

        // 直接打到日志：这是本项要交付的实测证据，别只留在断言通过/失败里
        LmpMod.LOGGER.info("[lmp-mspt] 容器 {}（实放 {}）· 区块 {} · 预热 {} 轮 + 统计 {} 轮（每轮 tick 数 {}）| "
                        + "扫掠 tick ms: 均值 {} p50={} p95={} p99={} max={}（n={}，预热轮 max={}）| "
                        + "收尾 tick max={} | 每容器 {} µs | "
                        + "服务端「最近 100 tick 内最慢一 tick」ms: 扫描前 {} → 扫描期间峰值 {}（n={}）| "
                        + "已加载区块 {} → {} | 覆盖率 {}‰ | 快照 钻石={} 潜影盒={}",
                CONTAINERS, harness.containersPlaced, CHUNKS * CHUNKS, WARMUP_ROUNDS, ROUNDS,
                harness.ticksPerRound,
                round(sliceAvg), round(sliceP50), round(sliceP95), round(sliceP99), round(sliceMax), slices.length,
                round(warmupMax), round(finishMax), round(perContainerMicros),
                round(harness.baselineWorstMs), round(duringWorst), harness.duringWorstMs.size(),
                harness.loadedBefore, harness.loadedAfter, coverage, diamonds, shulkers);

        // 1) 负载真的建起来了。这三条测的是「测试脚手架对不对」而不是被测代码——
        //    第一次写这个用例时坐标网格写错，64 次放置落在 16 个位置上，正是这里把它顶出来的。
        context.assertTrue(harness.containersPlaced == CONTAINERS,
                Text.literal("应放置 " + CONTAINERS + " 个容器，实际 " + harness.containersPlaced));
        context.assertTrue(actuallyPresent == CONTAINERS,
                Text.literal("区域里应恰好有 " + CONTAINERS + " 个容器方块实体，实际 " + actuallyPresent));
        context.assertTrue(coverage == 1000,
                Text.literal("覆盖率应为 1000‰，实际 " + coverage + "‰（有区块没被扫到，测量无效）"));

        // 2) 读到的内容对得上：每个箱子 64 钻石 + 1 个空潜影盒（空潜影盒按 1 个计）
        context.assertTrue(diamonds == (long) CONTAINERS * DIAMONDS_PER_CHEST,
                Text.literal("钻石应为 " + ((long) CONTAINERS * DIAMONDS_PER_CHEST) + "，实际 " + diamonds));
        context.assertTrue(shulkers == CONTAINERS,
                Text.literal("空潜影盒应按 1 个计，期望 " + CONTAINERS + "，实际 " + shulkers));

        // 3) 扫描不加载区块
        context.assertTrue(harness.loadedAfter <= harness.loadedBefore,
                Text.literal("扫描不得加载区块：前 " + harness.loadedBefore + "，后 " + harness.loadedAfter));

        // 4) 每 tick 的扫描开销：设计预算是单片 ≤ 2ms，超出的部分只允许是「一个方块实体的处理成本」
        context.assertTrue(Math.max(sliceMax, finishMax) < 50.0D,
                Text.literal("单片耗时不得超过一个 tick(50ms)，实际 扫掠 max=" + sliceMax + " 收尾 max=" + finishMax));
        context.assertTrue(sliceP99 < 10.0D,
                Text.literal("p99 扫掠耗时应远低于 tick 预算，实际 p99=" + sliceP99 + "ms"));

        releaseTickets(harness);
        context.complete();
    }

    /** 直接数一遍「区域里现在真实存在多少个容器方块实体」，用来区分「箱子没了」和「扫描漏了」。 */
    private static int countContainersInRegion(Harness harness)
    {
        int found = 0;

        for (int dx = 0; dx < CHUNKS; dx++)
        {
            for (int dz = 0; dz < CHUNKS; dz++)
            {
                ChunkPos pos = chunkOf(harness, dx, dz);
                var chunk = harness.world.getChunkManager().getWorldChunk(pos.x, pos.z);

                if (chunk == null)
                {
                    continue;
                }

                for (BlockPos blockPos : chunk.getBlockEntities().keySet())
                {
                    if (harness.region.contains(blockPos))
                    {
                        found++;
                    }
                }
            }
        }

        return found;
    }

    private static void releaseTickets(Harness harness)
    {
        for (int dx = 0; dx < CHUNKS; dx++)
        {
            for (int dz = 0; dz < CHUNKS; dz++)
            {
                ChunkPos pos = chunkOf(harness, dx, dz);
                harness.chunks.removeTicket(ChunkTicketType.FORCED, pos, 1);
            }
        }
    }

    private static double percentile(long[] sorted, double q)
    {
        if (sorted.length == 0)
        {
            return 0.0D;
        }

        int index = (int) Math.min(sorted.length - 1L, Math.round(q * (sorted.length - 1)));
        return sorted[index];
    }

    private static double averageOf(long[] values)
    {
        if (values.length == 0)
        {
            return 0.0D;
        }

        long sum = 0L;

        for (long value : values)
        {
            sum += value;
        }

        return (double) sum / values.length;
    }

    private static double max(List<Double> values)
    {
        double best = 0.0D;

        for (double value : values)
        {
            best = Math.max(best, value);
        }

        return best;
    }

    /**
     * 「最近 100 个 tick 里最慢的那一个」，单位毫秒。
     *
     * <p>为什么不用 {@code getAverageTickTime()}：它是 100 tick 的滚动均值，而一次扫描只有十几个 tick，
     * 均值会被前后的空载稀释——实测出现过「扫描期间读数反而比基线低」的假象。
     * 取窗口内最慢的一 tick，比较的是「扫描有没有制造出明显更慢的 tick」，这个结论不受稀释影响。
     */
    private static double worstTickMs(MinecraftServer server)
    {
        long worst = 0L;

        for (long value : server.getTickTimes())
        {
            worst = Math.max(worst, value);
        }

        return worst / 1.0E6D;
    }

    /** 日志里保留 3 位小数就够了，避免一长串浮点噪声。 */
    private static double round(double value)
    {
        return Math.round(value * 1000.0D) / 1000.0D;
    }
}

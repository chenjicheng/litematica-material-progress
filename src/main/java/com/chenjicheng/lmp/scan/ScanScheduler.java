package com.chenjicheng.lmp.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.material.RequiredMaterialSource;
import com.chenjicheng.lmp.region.MaterialRegion;
import com.chenjicheng.lmp.region.RegionStore;

import org.jetbrains.annotations.Nullable;

/**
 * 扫描调度器：全局单飞、每 tick 分片、可取消，并把结果发布给监听者（网络层）。
 *
 * <p>MSPT 保护策略：
 * <ul>
 *   <li>每 tick 只花 {@code min(2ms, 50ms - 平均 tick 耗时 - 余量)}；服务器本来就忙时整片跳过。</li>
 *   <li>单飞：同一时刻只允许一个扫描；重复请求合并为「排队一次」而不是排队多次。</li>
 *   <li>原理图解析（可能上百毫秒）放到独立工作线程，绝不在 tick 线程上做；解析完成后再回主线程发布。</li>
 *   <li>玩家请求按玩家限流，避免刷刷新按钮造成扫描风暴。</li>
 * </ul>
 */
public final class ScanScheduler
{
    /** 单 tick 最多用于扫描的时间。 */
    private static final double MAX_SLICE_MS = 2.0D;
    /** 目标 tick 时长（20 TPS）。 */
    private static final double TICK_BUDGET_MS = 50.0D;
    /** 留给其它逻辑的余量：接近满负荷时不再叠加扫描开销。 */
    private static final double SAFETY_MARGIN_MS = 5.0D;
    /** 扫描状态下发节流。 */
    private static final int STATUS_INTERVAL_TICKS = 10;
    /** 单玩家触发扫描的最小间隔。 */
    private static final long PLAYER_REQUEST_COOLDOWN_MS = 5_000L;
    /** 快照超过该时长后，客户端来取时会自动触发一次重扫。 */
    private static final long SNAPSHOT_TTL_MS = 10L * 60L * 1000L;

    public enum Phase
    {
        IDLE,
        SCANNING,
        FINALIZING
    }

    /** 扫描状态（下发给客户端用于显示进度）。 */
    public record ScanStatus(Phase phase, int chunksDone, int chunksTotal, int containersScanned, long revision)
    {
    }

    /** 监听者：网络层实现它，把结果推给客户端。 */
    public interface Listener
    {
        void onSnapshotUpdated(UUID regionId, MaterialProgress progress);

        void onStatusUpdated(UUID regionId, ScanStatus status);
    }

    private final RegionStore store;
    private final RequiredMaterialSource requiredSource;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<UUID, MaterialProgress> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, ScanStatus> statuses = new ConcurrentHashMap<>();
    private final Map<UUID, Optional<Map<Item, Integer>>> requiredCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();
    private final ExecutorService schematicExecutor = Executors.newSingleThreadExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "lmp-schematic-reader");
        thread.setDaemon(true);
        return thread;
    });

    private @Nullable RegionScanner scanner;
    private @Nullable MaterialRegion scanningRegion;
    private @Nullable UUID queuedRegionId;
    private int statusTickCounter;

    public ScanScheduler(RegionStore store, RequiredMaterialSource requiredSource)
    {
        this.store = store;
        this.requiredSource = requiredSource;
    }

    public void addListener(Listener listener)
    {
        this.listeners.add(listener);
    }

    // ------------------------------------------------------------ 查询

    public Optional<MaterialProgress> snapshot(UUID regionId)
    {
        return Optional.ofNullable(this.snapshots.get(regionId));
    }

    /**
     * 该区域<b>当前版本</b>的快照。
     *
     * <p>区域改过（改名、换绑定、改范围）之后 revision 会前进，此时内存里那份旧快照描述的已经不是
     * 当前区域，继续下发会让界面显示上一版区域的数据。所以版本对不上就当作没有快照。
     */
    public Optional<MaterialProgress> currentSnapshot(MaterialRegion region)
    {
        MaterialProgress snapshot = this.snapshots.get(region.id());
        return snapshot != null && snapshot.revision() == region.revision()
                ? Optional.of(snapshot)
                : Optional.empty();
    }

    public Optional<ScanStatus> status(UUID regionId)
    {
        return Optional.ofNullable(this.statuses.get(regionId));
    }

    public boolean isScanning(UUID regionId)
    {
        return this.scanningRegion != null && this.scanningRegion.id().equals(regionId);
    }

    // ------------------------------------------------------------ 请求

    /**
     * 请求扫描（或排队）。
     *
     * @param playerId 触发者，用于限流与诊断；{@code null} 表示系统触发（不受限流约束）
     * @return 失败原因（i18n 键后缀），成功返回空
     */
    public Optional<String> requestScan(MaterialRegion region, @Nullable UUID playerId)
    {
        if (playerId != null)
        {
            long now = System.currentTimeMillis();
            Long last = this.lastRequestAt.get(playerId);

            if (last != null && now - last < PLAYER_REQUEST_COOLDOWN_MS)
            {
                return Optional.of("rate_limited");
            }

            this.lastRequestAt.put(playerId, now);
        }

        if (this.scanningRegion != null && this.scanningRegion.id().equals(region.id()))
        {
            return Optional.empty();
        }

        this.queuedRegionId = region.id();
        return Optional.empty();
    }

    /** 取消当前扫描（服务器关闭等「全部停下」的场景）。 */
    public void cancel()
    {
        if (this.scanner != null)
        {
            this.scanner.cancel();
        }

        this.queuedRegionId = null;
    }

    /**
     * 只取消正在扫描的<b>这个</b>区域。
     *
     * <p>删除区域时必须用它而不是 {@link #cancel()}：后者会把另一个无关区域的扫描一起打断，
     * 而那个扫描既没被请求取消、也没人会重新触发。
     *
     * @return 是否确实取消了一个正在进行的扫描
     */
    public boolean cancelRegion(UUID regionId)
    {
        if (this.scanningRegion != null && this.scanningRegion.id().equals(regionId))
        {
            // 只停扫描器，**不**走 cancel()：后者会顺带清空排队中的区域，
            // 而那个排队请求属于另一个区域，跟这次取消没有关系
            if (this.scanner != null)
            {
                this.scanner.cancel();
            }

            return true;
        }

        if (regionId.equals(this.queuedRegionId))
        {
            this.queuedRegionId = null;
            return true;
        }

        return false;
    }

    public void onServerStopping()
    {
        this.cancel();
        this.schematicExecutor.shutdownNow();

        try
        {
            this.schematicExecutor.awaitTermination(1L, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 每 tick 推进

    public void tick(MinecraftServer server)
    {
        if (this.scanner == null && this.queuedRegionId != null)
        {
            // 先取走再启动：startScan 会发布状态、回调监听者，而监听者可能在这一瞬间排进新的请求。
            // 启动之后再无条件清空，会把那个刚排进来的请求一起抹掉。
            UUID queued = this.queuedRegionId;
            this.queuedRegionId = null;
            this.startScan(server, queued);
        }

        if (this.scanner == null)
        {
            return;
        }

        // 自适应预算：服务器已经很忙就整片跳过，绝不把接近满载的 tick 推过 50ms
        double averageTickMs = server.getAverageTickTime();
        double sliceMs = Math.min(MAX_SLICE_MS, TICK_BUDGET_MS - averageTickMs - SAFETY_MARGIN_MS);

        if (sliceMs <= 0.1D)
        {
            return;
        }

        long deadline = System.nanoTime() + (long) (sliceMs * 1_000_000.0D);
        boolean done = this.scanner.advance(deadline);
        this.publishStatusIfDue();

        if (done)
        {
            this.finishScan(server);
        }
    }

    private void startScan(MinecraftServer server, UUID regionId)
    {
        Optional<MaterialRegion> maybeRegion = this.store.byId(regionId);

        if (maybeRegion.isEmpty())
        {
            return;
        }

        MaterialRegion region = maybeRegion.get();
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, region.dimension());
        ServerWorld world = server.getWorld(key);

        if (world == null)
        {
            LmpMod.LOGGER.warn("材料区 {} 的维度 {} 当前不存在，跳过扫描", region.name(), region.dimension());
            return;
        }

        // regions.json 是磁盘上的文件，可以被手工改坏。语义非法的记录会让 chunks() 抛异常，
        // 而那个异常会从 tick 一路冒到服务端 tick 循环；更糟的是状态已经置成「正在扫描」，
        // 区域会永久卡在那里、连重新请求都不受理。因此先校验、先构造，再动状态。
        if (region.validate().isPresent())
        {
            LmpMod.LOGGER.warn("材料区「{}」的当前定义不合法，跳过扫描；请在界面里重新划定范围", region.name());
            return;
        }

        RegionScanner created;

        try
        {
            created = new RegionScanner(world, region);
        }
        catch (RuntimeException e)
        {
            LmpMod.LOGGER.error("材料区「{}」的扫描器构造失败，跳过这一轮", region.name(), e);
            return;
        }

        this.scanningRegion = region;
        this.scanner = created;
        this.statusTickCounter = 0;
        this.publishStatus(region.id(), new ScanStatus(Phase.SCANNING, 0, created.chunksTotal(), 0, region.revision()));
    }

    private void finishScan(MinecraftServer server)
    {
        RegionScanner finished = this.scanner;
        MaterialRegion region = this.scanningRegion;

        this.scanner = null;
        this.scanningRegion = null;

        if (finished == null || region == null)
        {
            return;
        }

        if (finished.isCancelled())
        {
            // 取消 = 结果已经没人要了（区域被删掉/服务器在停）。此时计数是半成品，
            // 若照常 store.put(...) 会把刚被删除的区域又写回存储里。
            this.publishStatus(region.id(), new ScanStatus(Phase.IDLE, finished.chunksDone(),
                    finished.chunksTotal(), finished.containersScanned(), region.revision()));
            LmpMod.LOGGER.info("材料区「{}」的扫描已取消，结果丢弃", region.name());
            return;
        }

        // 快照先按「所需未知」发布，保证界面立刻能看到已收集数量；原理图解析完成后会再更新一次
        Map<Item, Long> counts = finished.counts();
        int coverage = finished.coveragePermille();
        MaterialProgress.Status status = finished.resultStatus();
        long finishedAt = System.currentTimeMillis();
        Optional<MaterialRegion> maybeUpdated = this.store.applyScanResult(region.id(), finishedAt, coverage);

        if (maybeUpdated.isEmpty())
        {
            // 扫描期间区域被删掉了：这一轮结果作废，绝不把它写回去
            this.publishStatus(region.id(), new ScanStatus(Phase.IDLE, 0, 0, 0, region.revision()));
            LmpMod.LOGGER.info("材料区「{}」在扫描期间被删除，结果丢弃", region.name());
            return;
        }

        MaterialRegion updated = maybeUpdated.get();
        // 用**当前**区域（而不是扫描开始时捕获的那份）取所需材料：扫描期间玩家可能刚绑定好 placement，
        // 拿旧记录会让界面继续显示「所需未知」
        Optional<Map<Item, Integer>> required = this.cachedRequired(updated);

        MaterialProgress progress = required
                .map(map -> MaterialProgress.join(map, counts, status, coverage, updated.revision()))
                .orElseGet(() -> MaterialProgress.unknownRequired(counts, coverage, updated.revision()));

        this.snapshots.put(region.id(), progress);
        this.publishStatus(region.id(), new ScanStatus(Phase.IDLE, finished.chunksDone(), finished.chunksTotal(),
                finished.containersScanned(), updated.revision()));
        this.notifySnapshot(region.id(), progress);
        LmpMod.LOGGER.info("材料区「{}」扫描完成：{} 个容器，{} 种物品，覆盖率 {}‰，状态 {}",
                region.name(), finished.containersScanned(), counts.size(), coverage, status);

        if (required.isEmpty() && updated.placementId() != null)
        {
            this.startAsyncRequiredParse(server, updated);
        }
    }

    /** 触发一次异步的原理图解析，并把结果回主线程重新发布。 */
    private void startAsyncRequiredParse(MinecraftServer server, MaterialRegion region)
    {
        UUID placementId = region.placementId();

        if (placementId == null || this.requiredCache.containsKey(placementId))
        {
            return;
        }

        this.publishStatus(region.id(), new ScanStatus(Phase.FINALIZING, 0, 0, 0, region.revision()));
        this.schematicExecutor.execute(() ->
        {
            Optional<Map<Item, Integer>> parsed;

            try
            {
                parsed = this.requiredSource.requiredFor(placementId);
            }
            catch (Exception e)
            {
                LmpMod.LOGGER.error("解析原理图失败：placement={}", placementId, e);
                parsed = Optional.empty();
            }

            Optional<Map<Item, Integer>> result = parsed;

            if (result.isPresent())
            {
                this.requiredCache.put(placementId, result);
            }

            // 服务器停止后 MinecraftServer.execute 会直接在调用线程上跑，等于在工作线程上发布结果；
            // 因此这里先确认服务器还在跑，否则丢弃这次解析（下次扫描会重新解析）
            if (server.isRunning())
            {
                server.execute(() ->
                {
                    if (server.isRunning())
                    {
                        this.onRequiredParsed(server, region.id(), result.isPresent());
                    }
                });
            }
        });
    }

    private Optional<Map<Item, Integer>> cachedRequired(MaterialRegion region)
    {
        if (region.placementId() == null)
        {
            return Optional.empty();
        }

        return this.requiredCache.getOrDefault(region.placementId(), Optional.empty());
    }

    private void onRequiredParsed(MinecraftServer server, UUID regionId, boolean parsed)
    {
        // 解析在工作线程上跑，回来时同一区域可能已经开始新一轮扫描。此时发布「上一轮的快照 + IDLE」
        // 会把正在进行的扫描状态和内容一起回退，界面就卡在旧数据上。
        if (this.isScanning(regionId))
        {
            LmpMod.LOGGER.debug("材料区 {} 已开始新一轮扫描，丢弃这一轮原理图解析结果", regionId);
            return;
        }

        MaterialProgress existing = this.snapshots.get(regionId);
        long revision = this.store.byId(regionId).map(MaterialRegion::revision).orElse(0L);

        if (!parsed || existing == null)
        {
            if (!parsed)
            {
                LmpMod.LOGGER.warn("材料区 {} 的原理图无法解析，进度将显示为「所需未知」", regionId);
            }

            this.publishStatus(regionId, new ScanStatus(Phase.IDLE, 0, 0, 0, revision));
            return;
        }

        MaterialRegion region = this.store.byId(regionId).orElse(null);

        if (region == null)
        {
            return;
        }

        Map<Item, Integer> required = this.requiredCache.getOrDefault(region.placementId(), Optional.empty()).orElse(null);

        if (required == null)
        {
            return;
        }

        // 状态必须重算：这份快照当初是「所需未知」时发布的，直接沿用会让界面在材料已经算出来之后
        // 仍然显示「所需未知」。已知所需之后，剩下的不确定性只有扫描覆盖不全。
        MaterialProgress.Status status = existing.coveragePermille() >= 1000
                ? MaterialProgress.Status.COMPLETE
                : MaterialProgress.Status.PARTIAL;
        MaterialProgress filled = MaterialProgress.join(required, existing.present(), status,
                existing.coveragePermille(), region.revision());
        this.snapshots.put(regionId, filled);
        this.notifySnapshot(regionId, filled);
        this.publishStatus(regionId, new ScanStatus(Phase.IDLE, 0, 0, 0, region.revision()));
    }

    private void publishStatusIfDue()
    {
        this.statusTickCounter++;

        if (this.statusTickCounter < STATUS_INTERVAL_TICKS || this.scanner == null)
        {
            return;
        }

        this.statusTickCounter = 0;
        UUID regionId = this.scanningRegion == null ? null : this.scanningRegion.id();

        if (regionId == null)
        {
            return;
        }

        this.publishStatus(regionId, new ScanStatus(Phase.SCANNING, this.scanner.chunksDone(),
                this.scanner.chunksTotal(), this.scanner.containersScanned(), this.scanningRegion.revision()));
    }

    private void publishStatus(UUID regionId, ScanStatus status)
    {
        this.statuses.put(regionId, status);

        for (Listener listener : this.listeners)
        {
            listener.onStatusUpdated(regionId, status);
        }
    }

    private void notifySnapshot(UUID regionId, MaterialProgress progress)
    {
        for (Listener listener : this.listeners)
        {
            listener.onSnapshotUpdated(regionId, progress);
        }
    }

    /**
     * 客户端来取快照：过旧则顺带触发一次重扫（重扫策略的 TTL 部分）。
     *
     * <p>过期判定<b>只</b>看这个区域自己的上次扫描时间与版本号。早先这里混进了「全局最后一次扫描完成时间」，
     * 结果只要刚扫过<b>任意</b>一个区域，其它区域的旧快照都会被当成新鲜的，TTL 重扫被永久抑制。
     */
    public void onSnapshotRequested(MinecraftServer server, MaterialRegion region)
    {
        MaterialProgress existing = this.snapshots.get(region.id());
        boolean stale = existing == null
                || existing.revision() != region.revision()
                || System.currentTimeMillis() - region.lastScanAt() > SNAPSHOT_TTL_MS;

        if (stale && !this.isScanning(region.id()))
        {
            this.requestScan(region, null);
        }
    }

    /** 供诊断/测试：全部已知快照。 */
    public Map<UUID, MaterialProgress> snapshots()
    {
        return Map.copyOf(this.snapshots);
    }

    /** 缓存解析结果（供 M2 完成后直接注入，避免重复解析）。 */
    public void cacheRequired(UUID placementId, Map<Item, Integer> required)
    {
        this.requiredCache.put(placementId, Optional.of(Map.copyOf(required)));
    }

    public static Identifier dimensionId(ServerWorld world)
    {
        return world.getRegistryKey().getValue();
    }
}

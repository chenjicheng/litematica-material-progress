package com.chenjicheng.lmp.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import com.chenjicheng.lmp.count.ContainerCounter;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.region.MaterialRegion;

/**
 * 一次材料区扫描的游标状态机。由 {@link ScanScheduler} 每 tick 推进一小片。
 *
 * <p>关键设计（都是为了不抬高 MSPT）：
 * <ul>
 *   <li><b>不加载区块</b>：只用 {@code ServerChunkManager.getWorldChunk(cx, cz)}，未加载返回 null →
 *       跳过该区块并记入 {@code skippedChunks}（结果标记为 PARTIAL）。</li>
 *   <li><b>不跨 tick 持有区块/物品栈引用</b>：跨 tick 只保留「区块序号 + 该区块内 {@link BlockPos} 列表 +
 *       列表内序号」这些不可变数据；每次进入区块时重新取一次区块对象。</li>
 *   <li><b>预算检查在每个方块实体之前</b>（不是每个容器之前）：一个全是告示牌/床/刷怪笼的区块不会
 *       触发「容器上限」，只在容器处检查会直接冲过头。</li>
 * </ul>
 *
 * <p>必须只在服务端主线程使用：容器读取不是线程安全的。
 */
public final class RegionScanner
{
    private final ServerWorld world;
    private final MaterialRegion region;
    private final ChunkPos[] chunks;
    private final Map<Item, Long> counts = new LinkedHashMap<>();

    private int chunkIndex;
    private List<BlockPos> chunkPositions = List.of();
    private int positionIndex;
    private int scannedChunks;
    private int skippedChunks;
    private int containersScanned;
    private boolean finished;
    private volatile boolean cancelled;

    public RegionScanner(ServerWorld world, MaterialRegion region)
    {
        this.world = world;
        this.region = region;
        this.chunks = region.chunks();
    }

    /**
     * 推进扫描。
     *
     * @param deadlineNanos 本 tick 的时间上限（{@link System#nanoTime()} 基准）
     * @return true 表示扫描已结束（完成或被取消）
     */
    public boolean advance(long deadlineNanos)
    {
        while (this.chunkIndex < this.chunks.length)
        {
            if (this.cancelled)
            {
                this.finished = true;
                return true;
            }

            if (System.nanoTime() >= deadlineNanos)
            {
                return false;
            }

            ChunkPos chunkPos = this.chunks[this.chunkIndex];
            WorldChunk chunk = this.world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z);

            if (chunk == null)
            {
                // 未加载：不强制加载，跳过并让游标前进
                if (this.positionIndex == 0 && this.chunkPositions.isEmpty())
                {
                    this.skippedChunks++;
                }

                this.enterNextChunk();
                continue;
            }

            if (this.chunkPositions.isEmpty() && this.positionIndex == 0)
            {
                // 该区块的方块实体位置快照：BlockPos 不可变，可以安全跨 tick 保留
                this.chunkPositions = new ArrayList<>(chunk.getBlockEntities().keySet());
                this.scannedChunks++;
            }

            while (this.positionIndex < this.chunkPositions.size())
            {
                if (this.cancelled)
                {
                    this.finished = true;
                    return true;
                }

                if (System.nanoTime() >= deadlineNanos)
                {
                    return false;
                }

                BlockPos pos = this.chunkPositions.get(this.positionIndex++);

                if (this.region.contains(pos))
                {
                    this.scanPosition(chunk, pos);
                }
            }

            this.enterNextChunk();
        }

        this.finished = true;
        return true;
    }

    private void enterNextChunk()
    {
        this.chunkIndex++;
        this.chunkPositions = List.of();
        this.positionIndex = 0;
    }

    private void scanPosition(WorldChunk chunk, BlockPos pos)
    {
        BlockEntity blockEntity = chunk.getBlockEntity(pos);

        if (blockEntity == null || !ContainerFilter.isCounted(blockEntity))
        {
            return;
        }

        if (blockEntity instanceof Inventory inventory)
        {
            // 双箱的两半各自是独立的 27 格容器，分别统计既不会遗漏也不会重复
            ContainerCounter.countContainer(inventory, this.counts);
            this.containersScanned++;
        }
    }

    public void cancel()
    {
        this.cancelled = true;
    }

    public boolean isFinished()
    {
        return this.finished;
    }

    /** 已统计出的物品数量（只读视图）。 */
    public Map<Item, Long> counts()
    {
        return Map.copyOf(this.counts);
    }

    public int containersScanned()
    {
        return this.containersScanned;
    }

    public int chunksTotal()
    {
        return this.chunks.length;
    }

    /** 已处理的区块数（含跳过的）。 */
    public int chunksDone()
    {
        return Math.min(this.chunkIndex, this.chunks.length);
    }

    public int skippedChunks()
    {
        return this.skippedChunks;
    }

    /** 覆盖率千分比：全部区块都已加载并扫描为 1000。 */
    public int coveragePermille()
    {
        if (this.chunks.length == 0)
        {
            return 1000;
        }

        return (int) Math.round(1000.0D * this.scannedChunks / this.chunks.length);
    }

    /** 结果状态：有区块被跳过就是 PARTIAL。 */
    public MaterialProgress.Status resultStatus()
    {
        return this.skippedChunks > 0 ? MaterialProgress.Status.PARTIAL : MaterialProgress.Status.COMPLETE;
    }
}

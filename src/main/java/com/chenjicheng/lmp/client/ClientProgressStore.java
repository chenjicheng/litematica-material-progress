package com.chenjicheng.lmp.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 客户端的进度缓存：只保存服务端推来的数据，自己从不扫描。
 *
 * <p>界面从这个缓存读取；缓存更新时通知监听者（界面据此重绘）。
 */
public final class ClientProgressStore
{
    /** 扫描阶段（与服务端的 byte 编码对应，客户端不依赖服务端类）。 */
    public enum Phase
    {
        IDLE,
        SCANNING,
        FINALIZING;

        public static Phase fromByte(byte value)
        {
            return switch (value)
            {
                case 1 -> SCANNING;
                case 2 -> FINALIZING;
                default -> IDLE;
            };
        }
    }

    /** 扫描状态。 */
    public record Status(Phase phase, int chunksDone, int chunksTotal, int containersScanned, long revision)
    {
        public double fraction()
        {
            return this.chunksTotal <= 0 ? 0.0D : Math.min(1.0D, (double) this.chunksDone / (double) this.chunksTotal);
        }
    }

    private static final ClientProgressStore INSTANCE = new ClientProgressStore();

    private final Map<UUID, LmpPayloads.RegionInfo> regions = new LinkedHashMap<>();
    private final Map<UUID, MaterialProgress> progress = new HashMap<>();
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean serverSupportsUs;

    private ClientProgressStore()
    {
    }

    public static ClientProgressStore get()
    {
        return INSTANCE;
    }

    public void addListener(Runnable listener)
    {
        this.listeners.add(listener);
    }

    public void removeListener(Runnable listener)
    {
        this.listeners.remove(listener);
    }

    /**
     * 当前注册的监听者数量。
     *
     * <p>界面在打开时注册、关闭时注销；这个数字应当总是回到 0，否则每开关一次界面就泄漏一个监听者。
     * 供诊断与客户端 GameTest 断言使用。
     */
    public int listenerCount()
    {
        return this.listeners.size();
    }

    private void notifyListeners()
    {
        for (Runnable listener : this.listeners)
        {
            listener.run();
        }
    }

    /** 服务端是否安装并响应了本模组。 */
    public boolean serverSupportsUs()
    {
        return this.serverSupportsUs;
    }

    public void reset()
    {
        this.regions.clear();
        this.progress.clear();
        this.statuses.clear();
        this.serverSupportsUs = false;
        this.notifyListeners();
    }

    public void setRegions(List<LmpPayloads.RegionInfo> infos)
    {
        this.serverSupportsUs = true;
        this.regions.clear();

        for (LmpPayloads.RegionInfo info : infos)
        {
            this.regions.put(info.id(), info);
        }

        // 服务端已删除的区域，客户端缓存也要清掉，避免界面显示幽灵条目
        this.progress.keySet().retainAll(this.regions.keySet());
        this.statuses.keySet().retainAll(this.regions.keySet());
        this.notifyListeners();
    }

    public List<LmpPayloads.RegionInfo> regions()
    {
        return List.copyOf(this.regions.values());
    }

    public Optional<LmpPayloads.RegionInfo> regionById(UUID id)
    {
        return Optional.ofNullable(this.regions.get(id));
    }

    /** 按共享放置找材料区（一个 placement 一个区域）。 */
    public Optional<LmpPayloads.RegionInfo> regionForPlacement(UUID placementId)
    {
        return this.regions.values().stream()
                .filter(info -> placementId.equals(info.placementId()))
                .findFirst();
    }

    public void setProgress(UUID regionId, long revision, byte statusByte, int coveragePermille,
                            List<LmpPayloads.Entry> entries)
    {
        MaterialProgress.Status status = switch (statusByte)
        {
            case 1 -> MaterialProgress.Status.PARTIAL;
            case 2 -> MaterialProgress.Status.UNKNOWN_REQUIRED;
            default -> MaterialProgress.Status.COMPLETE;
        };

        Map<Item, Integer> required = new LinkedHashMap<>();
        Map<Item, Long> present = new LinkedHashMap<>();

        for (LmpPayloads.Entry entry : entries)
        {
            // 未知物品（模组被移除等）会解析成 air，直接跳过，不让界面崩
            Item item = Registries.ITEM.get(entry.itemId());

            if (item == null || item == net.minecraft.item.Items.AIR)
            {
                continue;
            }

            if (entry.required() > 0)
            {
                required.put(item, entry.required());
            }

            if (entry.present() > 0L)
            {
                present.put(item, entry.present());
            }
        }

        // 乱序到达的旧快照不能覆盖新快照：否则界面会退回上一版数据
        MaterialProgress existing = this.progress.get(regionId);

        if (existing != null && revision < existing.revision())
        {
            return;
        }

        this.progress.put(regionId, MaterialProgress.join(required, present, status, coveragePermille, revision));
        this.notifyListeners();
    }

    public Optional<MaterialProgress> progress(UUID regionId)
    {
        return Optional.ofNullable(this.progress.get(regionId));
    }

    public void setStatus(UUID regionId, byte phase, long revision, int chunksDone, int chunksTotal,
                          int containersScanned)
    {
        this.statuses.put(regionId, new Status(Phase.fromByte(phase), chunksDone, chunksTotal, containersScanned, revision));
        this.notifyListeners();
    }

    public Optional<Status> status(UUID regionId)
    {
        return Optional.ofNullable(this.statuses.get(regionId));
    }

    /** 供界面显示：某个区域当前是否正在扫描。 */
    public boolean isScanning(UUID regionId)
    {
        Status status = this.statuses.get(regionId);
        return status != null && status.phase() != Phase.IDLE;
    }

    /** 便于诊断。 */
    public static Identifier itemId(Item item)
    {
        return Registries.ITEM.getId(item);
    }
}

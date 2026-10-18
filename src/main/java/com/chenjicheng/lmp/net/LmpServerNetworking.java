package com.chenjicheng.lmp.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.region.MaterialRegion;
import com.chenjicheng.lmp.region.RegionStore;
import com.chenjicheng.lmp.scan.ScanScheduler;

import org.jetbrains.annotations.Nullable;

/**
 * 服务端网络：接收客户端请求，推送区域清单、进度快照与扫描状态。
 *
 * <p>所有发送都先检查 {@code canSend}：客户端没装本模组时不会报错，只是收不到数据；
 * 客户端据此显示「服务端未安装」的状态。
 */
public final class LmpServerNetworking implements ScanScheduler.Listener
{
    private final RegionStore store;
    private final ScanScheduler scheduler;
    private @Nullable MinecraftServer server;

    public LmpServerNetworking(RegionStore store, ScanScheduler scheduler)
    {
        this.store = store;
        this.scheduler = scheduler;
    }

    public void register()
    {
        ServerPlayNetworking.registerGlobalReceiver(LmpPayloads.RequestPayload.ID, (payload, context) ->
                context.server().execute(() -> this.handleRequest(context.server(), context.player(), payload)));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
        {
            this.server = server;
            this.sendRegionList(handler.getPlayer());
        });
    }

    /** 区域发生变更后调用：让所有在线客户端刷新区域清单。 */
    public void broadcastRegionList()
    {
        if (this.server == null)
        {
            return;
        }

        for (ServerPlayerEntity player : this.server.getPlayerManager().getPlayerList())
        {
            this.sendRegionList(player);
        }
    }

    private void sendRegionList(ServerPlayerEntity player)
    {
        List<LmpPayloads.RegionInfo> infos = new ArrayList<>();

        for (MaterialRegion region : this.store.all())
        {
            infos.add(new LmpPayloads.RegionInfo(region.id(), region.name(), region.dimension().toString(),
                    region.placementId(), region.pos1(), region.pos2(),
                    region.revision(), region.lastScanAt(), region.coveragePermille()));
        }

        if (ServerPlayNetworking.canSend(player, LmpPayloads.RegionListPayload.ID))
        {
            ServerPlayNetworking.send(player, new LmpPayloads.RegionListPayload(List.copyOf(infos)));
        }
    }

    private void handleRequest(MinecraftServer server, ServerPlayerEntity player, LmpPayloads.RequestPayload request)
    {
        MaterialRegion region = this.store.byId(request.regionId()).orElse(null);

        if (region == null)
        {
            return;
        }

        // 有快照就直接回，再考虑是否需要按 TTL 触发重扫；没有快照则回状态并触发扫描
        this.scheduler.snapshot(region.id()).ifPresent(progress -> this.sendSnapshot(player, region.id(), progress));
        this.scheduler.status(region.id()).ifPresent(status -> this.sendStatus(player, region.id(), status));

        if (request.forceRescan())
        {
            // 手动刷新走「玩家触发」路径，因此受限流约束；被限流时明确告知，而不是静默无反应
            this.scheduler.requestScan(region, player.getUuid()).ifPresent(reason ->
                    player.sendMessage(Text.translatable("lmp.command.scan." + reason), true));
            return;
        }

        this.scheduler.onSnapshotRequested(server, region);
    }

    @Override
    public void onSnapshotUpdated(UUID regionId, MaterialProgress progress)
    {
        if (this.server == null)
        {
            return;
        }

        for (ServerPlayerEntity player : this.server.getPlayerManager().getPlayerList())
        {
            this.sendSnapshot(player, regionId, progress);
        }
    }

    @Override
    public void onStatusUpdated(UUID regionId, ScanScheduler.ScanStatus status)
    {
        if (this.server == null)
        {
            return;
        }

        for (ServerPlayerEntity player : this.server.getPlayerManager().getPlayerList())
        {
            this.sendStatus(player, regionId, status);
        }
    }

    private void sendSnapshot(ServerPlayerEntity player, UUID regionId, MaterialProgress progress)
    {
        if (!ServerPlayNetworking.canSend(player, LmpPayloads.SnapshotPayload.ID))
        {
            return;
        }

        List<LmpPayloads.Entry> entries = toEntries(progress);
        byte status = switch (progress.status())
        {
            case COMPLETE -> 0;
            case PARTIAL -> 1;
            case UNKNOWN_REQUIRED -> 2;
        };

        ServerPlayNetworking.send(player, new LmpPayloads.SnapshotPayload(
                regionId, progress.revision(), status, progress.coveragePermille(), entries));
    }

    private static List<LmpPayloads.Entry> toEntries(MaterialProgress progress)
    {
        Map<Item, LmpPayloads.Entry> entries = new LinkedHashMap<>();

        for (Map.Entry<Item, Integer> entry : progress.required().entrySet())
        {
            entries.put(entry.getKey(), new LmpPayloads.Entry(Registries.ITEM.getId(entry.getKey()),
                    entry.getValue(), progress.presentFor(entry.getKey())));
        }

        for (Map.Entry<Item, Long> entry : progress.present().entrySet())
        {
            if (!progress.required().containsKey(entry.getKey()))
            {
                entries.put(entry.getKey(), new LmpPayloads.Entry(Registries.ITEM.getId(entry.getKey()),
                        0, entry.getValue()));
            }
        }

        List<LmpPayloads.Entry> result = new ArrayList<>(entries.values());
        result.sort((a, b) -> a.itemId().toString().compareTo(b.itemId().toString()));

        if (result.size() > LmpPayloads.MAX_ENTRIES)
        {
            LmpMod.LOGGER.warn("快照条目过多（{}），已截断到 {}", result.size(), LmpPayloads.MAX_ENTRIES);
            return List.copyOf(result.subList(0, LmpPayloads.MAX_ENTRIES));
        }

        return List.copyOf(result);
    }

    private void sendStatus(ServerPlayerEntity player, UUID regionId, ScanScheduler.ScanStatus status)
    {
        if (!ServerPlayNetworking.canSend(player, LmpPayloads.StatusPayload.ID))
        {
            return;
        }

        byte phase = switch (status.phase())
        {
            case IDLE -> 0;
            case SCANNING -> 1;
            case FINALIZING -> 2;
        };

        ServerPlayNetworking.send(player, new LmpPayloads.StatusPayload(regionId, phase, status.revision(),
                status.chunksDone(), status.chunksTotal(), status.containersScanned()));
    }
}

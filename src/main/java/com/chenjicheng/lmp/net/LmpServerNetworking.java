package com.chenjicheng.lmp.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.region.MaterialRegion;
import com.chenjicheng.lmp.region.RegionEditor;
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
    /**
     * 有成本或会改状态的操作的权限闸，与 {@code /lmp scan}、{@code /lmp region} 完全相同
     * （1.21.11 的 GAMEMASTERS 对应旧的权限等级 2）。
     *
     * <p>界面走 payload 不等于放宽权限：这条通道只是把同样的动作换成类型化字段，
     * 服务端的校验、权限与限流一条都不少。
     */
    private static final Predicate<ServerCommandSource> EDIT_PERMISSION =
            CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK);

    /**
     * 同一玩家编辑材料区的最小间隔。
     *
     * <p>一次保存或删除都会在当前 tick 上同步重写整个 {@code regions.json}。命令那侧由人手输入、
     * 又有权限闸挡着，而 payload 这侧是可以被脚本刷的——没有这个间隔，一个改过的客户端就能让
     * 服务端主线程持续做文件 I/O。
     */
    private static final long EDIT_COOLDOWN_MS = 1_000L;

    /**
     * 当前这一轮服务器生命周期对应的网络层。
     *
     * <p>Fabric 的全局接收器与 JOIN 监听器<b>只能注册一次</b>（重复注册会静默失败、监听器则不断累积），
     * 而集成服务器可以在同一个 JVM 里反复起停。因此接收器只注册一次，并且不在闭包里捕获某一轮的实例，
     * 而是每次回调都取「当前」实例——否则重启之后仍然由上一张地图的 store/scheduler 提供服务。
     */
    private static volatile @Nullable LmpServerNetworking current;
    private static boolean registered;

    private final RegionStore store;
    private final ScanScheduler scheduler;
    private final RegionEditor editor;
    /** 只在服务端主线程访问（接收器都经 {@code context.server().execute} 调度）。 */
    private final Map<UUID, Long> lastEditAt = new HashMap<>();
    private @Nullable MinecraftServer server;

    public LmpServerNetworking(RegionStore store, ScanScheduler scheduler, RegionEditor editor)
    {
        this.store = store;
        this.scheduler = scheduler;
        this.editor = editor;
    }

    public void register()
    {
        current = this;

        if (registered)
        {
            return;
        }

        registered = true;

        ServerPlayNetworking.registerGlobalReceiver(LmpPayloads.RequestPayload.ID, (payload, context) ->
                context.server().execute(() ->
                {
                    LmpServerNetworking net = current;

                    if (net != null)
                    {
                        net.handleRequest(context.server(), context.player(), payload);
                    }
                }));

        ServerPlayNetworking.registerGlobalReceiver(LmpPayloads.RegionSavePayload.ID, (payload, context) ->
                context.server().execute(() ->
                {
                    LmpServerNetworking net = current;

                    if (net != null)
                    {
                        net.handleRegionSave(context.server(), context.player(), payload);
                    }
                }));

        ServerPlayNetworking.registerGlobalReceiver(LmpPayloads.RegionDeletePayload.ID, (payload, context) ->
                context.server().execute(() ->
                {
                    LmpServerNetworking net = current;

                    if (net != null)
                    {
                        net.handleRegionDelete(context.player(), payload);
                    }
                }));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
        {
            LmpServerNetworking net = current;

            if (net != null)
            {
                net.server = server;
                net.sendRegionList(handler.getPlayer());
            }
        });
    }

    /**
     * 服务器停止：把自己从「当前实例」上摘掉。
     *
     * <p>接收器本身没法注销（Fabric 没有这个 API），但至少不要让 {@link #current} 继续指向
     * 一个已经停掉的服务器上的实例。
     */
    public void unregister()
    {
        if (current == this)
        {
            current = null;
        }
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

        // 有「当前版本」的快照就直接回，再考虑是否需要按 TTL 触发重扫；没有快照则回状态并触发扫描
        this.scheduler.currentSnapshot(region).ifPresent(progress -> this.sendSnapshot(player, region.id(), progress));
        this.scheduler.status(region.id()).ifPresent(status -> this.sendStatus(player, region.id(), status));

        if (request.forceRescan())
        {
            // 强制重扫会让服务端做一次全区域扫描，属于有成本的操作，因此与 /lmp scan 用同一道权限闸；
            // 只读地取快照则对所有玩家开放（下面这条路径）。
            if (!EDIT_PERMISSION.test(player.getCommandSource()))
            {
                player.sendMessage(Text.translatable("lmp.msg.scan.not_permitted"), true);
                return;
            }

            // 手动刷新走「玩家触发」路径，因此受限流约束；被限流时明确告知，而不是静默无反应
            this.scheduler.requestScan(region, player.getUuid()).ifPresent(reason ->
                    player.sendMessage(Text.translatable("lmp.msg.scan." + reason), true));
            return;
        }

        this.scheduler.onSnapshotRequested(server, region);
    }

    // ------------------------------------------------------------ 区域编辑（界面入口）

    /**
     * 客户端在设置界面点了「保存」。
     *
     * <p>这一层只做信任边界上的检查：维度必须是本服务器上真实存在的世界。区域自身的约束
     * （体积、区块数、名称、绑定唯一性）由 {@link RegionEditor} 判定，与控制台命令共用同一套。
     */
    private void handleRegionSave(MinecraftServer server, ServerPlayerEntity player,
                                  LmpPayloads.RegionSavePayload payload)
    {
        if (!this.allowedToEdit(player) || this.throttled(player))
        {
            return;
        }

        Identifier dimension = Identifier.tryParse(payload.dimension());

        if (dimension == null || server.getWorld(RegistryKey.of(RegistryKeys.WORLD, dimension)) == null)
        {
            // 落库只会得到一个永远扫不到东西的区域，不如当场拒绝
            player.sendMessage(Text.translatable("lmp.msg.region.bad_dimension", payload.dimension()), false);
            return;
        }

        RegionEditor.Result result = this.editor.save(payload.regionId(), payload.name(), dimension,
                payload.pos1(), payload.pos2(), payload.placementId(), player.getUuid());

        this.report(player, result);

        if (result.ok())
        {
            this.broadcastRegionList();
        }
    }

    /** 客户端在设置界面点了「删除」。 */
    private void handleRegionDelete(ServerPlayerEntity player, LmpPayloads.RegionDeletePayload payload)
    {
        if (!this.allowedToEdit(player) || this.throttled(player))
        {
            return;
        }

        RegionEditor.Result result = this.editor.delete(payload.regionId());

        this.report(player, result);

        if (result.ok())
        {
            this.broadcastRegionList();
        }
    }

    /** 太频繁就明确回绝：一次编辑要同步写一遍整个文件，不能让客户端连着刷。 */
    private boolean throttled(ServerPlayerEntity player)
    {
        long now = System.currentTimeMillis();
        Long last = this.lastEditAt.get(player.getUuid());

        if (last != null && now - last < EDIT_COOLDOWN_MS)
        {
            player.sendMessage(Text.translatable("lmp.msg.region.rate_limited"), false);
            return true;
        }

        this.lastEditAt.put(player.getUuid(), now);
        return false;
    }

    /** 权限不足时明确回绝：payload 不得成为命令权限的旁路。 */
    private boolean allowedToEdit(ServerPlayerEntity player)
    {
        if (EDIT_PERMISSION.test(player.getCommandSource()))
        {
            return true;
        }

        player.sendMessage(Text.translatable("lmp.msg.not_permitted"), false);
        return false;
    }

    /** 把编辑结果，以及「内存里成功但没落盘」这类提醒，一起发回给发起者。 */
    private void report(ServerPlayerEntity player, RegionEditor.Result result)
    {
        player.sendMessage(Text.translatable(result.messageKey(), result.argsArray()), false);

        if (result.warningKey() != null)
        {
            player.sendMessage(Text.translatable(result.warningKey()), false);
        }
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

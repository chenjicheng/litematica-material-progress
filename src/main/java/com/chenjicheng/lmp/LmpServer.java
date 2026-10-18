package com.chenjicheng.lmp;

import java.util.Optional;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import com.chenjicheng.lmp.command.LmpCommand;
import com.chenjicheng.lmp.command.RegionSelectionState;
import com.chenjicheng.lmp.material.LitematicRequiredMaterialSource;
import com.chenjicheng.lmp.material.RequiredMaterialSource;
import com.chenjicheng.lmp.material.UnknownRequiredMaterialSource;
import com.chenjicheng.lmp.net.LmpPayloads;
import com.chenjicheng.lmp.net.LmpServerNetworking;
import com.chenjicheng.lmp.region.RegionStore;
import com.chenjicheng.lmp.scan.ScanScheduler;

import org.jetbrains.annotations.Nullable;

/**
 * 服务端侧的组合根：持有材料区存储、扫描调度器与网络层。
 *
 * <p>生命周期：模组初始化时注册事件与命令；服务器启动完成后加载存储（存档目录此时才确定），
 * 并把所有区域版本号推进一格，使客户端不会继续用重启前的缓存。
 */
public final class LmpServer
{
    private static @Nullable RegionStore store;
    private static @Nullable ScanScheduler scheduler;
    private static @Nullable LmpServerNetworking networking;
    private static RequiredMaterialSource requiredSource = UnknownRequiredMaterialSource.INSTANCE;
    private static final RegionSelectionState SELECTIONS = new RegionSelectionState();

    private LmpServer()
    {
    }

    public static void initialize()
    {
        LmpPayloads.registerPayloadTypes();
        LmpCommand.register();

        // 必须在服务器启动（也就是扫描调度器构造）之前接上：调度器持有这个引用
        setRequiredSource(new LitematicRequiredMaterialSource());

        ServerLifecycleEvents.SERVER_STARTED.register(LmpServer::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(LmpServer::onServerStopping);
        // 待定的角点是「这个玩家正在框这块区域」的临时状态，玩家一走或服务器一停就不该再留着，
        // 否则下次进来点 create 会用到上一次会话的坐标
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SELECTIONS.clear(handler.getPlayer().getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(LmpServer::onEndTick);

        LmpMod.LOGGER.info("{} 服务端组件已注册", LmpMod.MOD_ID);
    }

    private static void onServerStarted(MinecraftServer server)
    {
        RegionStore loaded = RegionStore.load(RegionStore.fileFor(server.getSavePath(WorldSavePath.ROOT)));
        loaded.onServerStart();
        store = loaded;

        ScanScheduler scanScheduler = new ScanScheduler(loaded, requiredSource);
        LmpServerNetworking net = new LmpServerNetworking(loaded, scanScheduler);
        scanScheduler.addListener(net);
        net.register();

        scheduler = scanScheduler;
        networking = net;

        LmpMod.LOGGER.info("材料区存储已加载：{} 个区域", loaded.all().size());
    }

    private static void onServerStopping(MinecraftServer server)
    {
        if (scheduler != null)
        {
            scheduler.onServerStopping();
        }

        if (store != null)
        {
            store.save();
        }

        store = null;
        scheduler = null;
        networking = null;
        SELECTIONS.clearAll();
    }

    private static void onEndTick(MinecraftServer server)
    {
        ScanScheduler scanScheduler = scheduler;

        if (scanScheduler != null)
        {
            // 必须由事件驱动：扫描在这里分片推进，绝不在别处同步执行
            scanScheduler.tick(server);
        }
    }

    public static Optional<RegionStore> store()
    {
        return Optional.ofNullable(store);
    }

    public static Optional<ScanScheduler> scheduler()
    {
        return Optional.ofNullable(scheduler);
    }

    /** 区域数据变更后通知所有客户端刷新清单。 */
    public static void onRegionsChanged()
    {
        LmpServerNetworking net = networking;

        if (net != null)
        {
            net.broadcastRegionList();
        }
    }

    public static RegionSelectionState selections()
    {
        return SELECTIONS;
    }

    public static RequiredMaterialSource requiredSource()
    {
        return requiredSource;
    }

    /**
     * 接入「所需材料」来源（M2 的原理图解析器）。只能在服务器启动前调用——
     * {@code ScanScheduler} 在构造时就把引用拿走了，之后再换不会生效。
     */
    public static void setRequiredSource(RequiredMaterialSource source)
    {
        requiredSource = source;
    }
}

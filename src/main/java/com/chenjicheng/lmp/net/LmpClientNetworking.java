package com.chenjicheng.lmp.net;

import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.client.ClientProgressStore;

import org.jetbrains.annotations.Nullable;

/**
 * 客户端网络：接收服务端推送，并按需请求快照。
 *
 * <p>接收器里只更新缓存，界面通过监听者刷新；所有发送都用 {@code canSend} 守卫，
 * 服务端没装本模组时直接降级为「服务端未安装」而不是抛异常。
 */
public final class LmpClientNetworking
{
    private LmpClientNetworking()
    {
    }

    public static void register()
    {
        // 断开连接必须清缓存：换服务器后旧区域的进度留在界面上比空着更误导。
        // 这个事件在 netty 线程上触发，而 reset() 会同步回调监听者（其中就有正在显示的界面），
        // 因此必须切回客户端主线程再清。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(() -> ClientProgressStore.get().reset()));

        ClientPlayNetworking.registerGlobalReceiver(LmpPayloads.RegionListPayload.ID, (payload, context) ->
                context.client().execute(() -> ClientProgressStore.get().setRegions(payload.regions())));

        ClientPlayNetworking.registerGlobalReceiver(LmpPayloads.SnapshotPayload.ID, (payload, context) ->
                context.client().execute(() -> ClientProgressStore.get().setProgress(payload.regionId(),
                        payload.revision(), payload.status(), payload.coveragePermille(), payload.entries())));

        ClientPlayNetworking.registerGlobalReceiver(LmpPayloads.StatusPayload.ID, (payload, context) ->
                context.client().execute(() -> ClientProgressStore.get().setStatus(payload.regionId(),
                        payload.phase(), payload.revision(), payload.chunksDone(), payload.chunksTotal(),
                        payload.containersScanned())));
    }

    /** 服务端是否支持本模组（是否能向它发送请求）。 */
    public static boolean canRequest()
    {
        return ClientPlayNetworking.canSend(LmpPayloads.RequestPayload.ID);
    }

    /** 服务端是否接受区域保存。与 {@link #canRequest()} 分开判断：旧版服务端可能只认前几个 payload。 */
    public static boolean canSaveRegion()
    {
        return canSend(LmpPayloads.RegionSavePayload.ID);
    }

    /** 服务端是否接受区域删除。 */
    public static boolean canDeleteRegion()
    {
        return canSend(LmpPayloads.RegionDeletePayload.ID);
    }

    /**
     * 请求某个区域的快照。
     *
     * @param knownRevision 客户端已有的版本号，服务端据此判断是否需要回包
     * @param forceRescan   玩家显式要求重扫（会绕过快照 TTL，但仍受服务端按玩家限流）
     */
    public static void requestSnapshot(UUID regionId, long knownRevision, boolean forceRescan)
    {
        if (canRequest())
        {
            ClientPlayNetworking.send(new LmpPayloads.RequestPayload(regionId, knownRevision, forceRescan));
        }
    }

    /**
     * 保存（新建或更新）一个材料区。
     *
     * @param regionId {@code null} 表示新建
     */
    public static boolean saveRegion(@Nullable UUID regionId, String name, Identifier dimension,
                                     BlockPos pos1, BlockPos pos2, @Nullable UUID placementId)
    {
        if (!canSaveRegion())
        {
            return false;
        }

        ClientPlayNetworking.send(new LmpPayloads.RegionSavePayload(regionId, name, dimension.toString(),
                pos1, pos2, placementId));
        return true;
    }

    /**
     * 删除一个材料区。
     *
     * @return 是否真的发出去了——服务端不认这个 payload 时返回 false，调用方据此给出提示
     */
    public static boolean deleteRegion(UUID regionId)
    {
        if (!canDeleteRegion())
        {
            return false;
        }

        ClientPlayNetworking.send(new LmpPayloads.RegionDeletePayload(regionId));
        return true;
    }

    /**
     * 能否发送某个 payload。
     *
     * <p>不能只判断「服务端装了本模组」：服务端可能装了旧版本，只认其中一部分 payload。
     * 逐个判断比一个总开关更准确，缺哪个就安静地不发，由调用方给出提示。
     */
    private static boolean canSend(CustomPayload.Id<? extends CustomPayload> id)
    {
        return ClientPlayNetworking.canSend(id);
    }
}

package com.chenjicheng.lmp.net;

import java.util.UUID;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.chenjicheng.lmp.client.ClientProgressStore;

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
}

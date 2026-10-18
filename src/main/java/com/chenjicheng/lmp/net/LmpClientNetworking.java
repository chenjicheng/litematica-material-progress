package com.chenjicheng.lmp.net;

import java.util.UUID;

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

    /** 请求某个区域的快照；{@code knownRevision} 用于服务端判断缓存是否仍然有效。 */
    public static void requestSnapshot(UUID regionId, long knownRevision)
    {
        if (canRequest())
        {
            ClientPlayNetworking.send(new LmpPayloads.RequestPayload(regionId, knownRevision));
        }
    }
}

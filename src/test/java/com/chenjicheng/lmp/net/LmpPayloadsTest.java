package com.chenjicheng.lmp.net;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.Unpooled;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 协议编解码的往返测试。
 *
 * <p>读写不对称是网络代码最典型的缺陷，而且只在真实连接上才暴露：写进去一个 VarInt、读出来按
 * VarLong 解，本地单机测试永远碰不到。这里对每个 payload 做一次「编码 → 解码」，把不对称钉死在单测里。
 *
 * <p>用 {@link DynamicRegistryManager#EMPTY} 构造缓冲区：本模组的字段只用原版标量、UUID、
 * Identifier 与 BlockPos，不依赖注册表内容。
 */
class LmpPayloadsTest
{
    private static RegistryByteBuf buffer()
    {
        return new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
    }

    private static <T extends CustomPayload> T roundTrip(T payload, net.minecraft.network.codec.PacketCodec<RegistryByteBuf, T> codec)
    {
        RegistryByteBuf buf = buffer();
        codec.encode(buf, payload);
        T decoded = codec.decode(buf);

        assertEquals(0, buf.readableBytes(), "解码后不应剩下未读字节：" + payload.getClass().getSimpleName());
        return decoded;
    }

    @Test
    void requestSurvivesARoundTrip()
    {
        UUID regionId = UUID.randomUUID();
        LmpPayloads.RequestPayload decoded = roundTrip(
                new LmpPayloads.RequestPayload(regionId, 1234567890123L, true), LmpPayloads.RequestPayload.CODEC);

        assertEquals(regionId, decoded.regionId());
        assertEquals(1234567890123L, decoded.knownRevision());
        assertEquals(true, decoded.forceRescan());
    }

    @Test
    void regionListSurvivesARoundTrip()
    {
        LmpPayloads.RegionInfo withPlacement = new LmpPayloads.RegionInfo(UUID.randomUUID(), "主基地仓库",
                "minecraft:overworld", UUID.randomUUID(), new BlockPos(-1, 60, 8), new BlockPos(15, 79, 31),
                42L, 1700000000000L, 997);
        LmpPayloads.RegionInfo withoutPlacement = new LmpPayloads.RegionInfo(UUID.randomUUID(), "未绑定",
                "minecraft:the_nether", null, new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), 1L, 0L, 0);

        LmpPayloads.RegionListPayload decoded = roundTrip(
                new LmpPayloads.RegionListPayload(List.of(withPlacement, withoutPlacement)),
                LmpPayloads.RegionListPayload.CODEC);

        assertEquals(List.of(withPlacement, withoutPlacement), decoded.regions());
    }

    @Test
    void snapshotSurvivesARoundTrip()
    {
        LmpPayloads.SnapshotPayload decoded = roundTrip(new LmpPayloads.SnapshotPayload(UUID.randomUUID(), 7L, (byte) 2,
                1000, List.of(new LmpPayloads.Entry(Identifier.of("minecraft:stone"), 1000, 640L),
                        new LmpPayloads.Entry(Identifier.of("minecraft:dirt"), 0, 12345L))),
                LmpPayloads.SnapshotPayload.CODEC);

        assertEquals(2, decoded.entries().size());
        assertEquals(Identifier.of("minecraft:stone"), decoded.entries().get(0).itemId());
        assertEquals(1000, decoded.entries().get(0).required());
        assertEquals(640L, decoded.entries().get(0).present());
        assertEquals(12345L, decoded.entries().get(1).present());
    }

    @Test
    void statusSurvivesARoundTrip()
    {
        UUID regionId = UUID.randomUUID();
        LmpPayloads.StatusPayload decoded = roundTrip(
                new LmpPayloads.StatusPayload(regionId, (byte) 1, 9L, 12, 256, 4096), LmpPayloads.StatusPayload.CODEC);

        assertEquals(regionId, decoded.regionId());
        assertEquals((byte) 1, decoded.phase());
        assertEquals(9L, decoded.revision());
        assertEquals(12, decoded.chunksDone());
        assertEquals(256, decoded.chunksTotal());
        assertEquals(4096, decoded.containersScanned());
    }

    // ------------------------------------------------------------ 区域编辑

    @Test
    void regionSaveSurvivesARoundTrip()
    {
        UUID regionId = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        LmpPayloads.RegionSavePayload decoded = roundTrip(new LmpPayloads.RegionSavePayload(regionId, "主基地 仓库",
                "minecraft:overworld", new BlockPos(-1, 60, 8), new BlockPos(15, 79, 31), placementId),
                LmpPayloads.RegionSavePayload.CODEC);

        assertEquals(regionId, decoded.regionId());
        assertEquals("主基地 仓库", decoded.name(), "区域名可以含空格：界面是主要入口，不再受命令参数分词限制");
        assertEquals("minecraft:overworld", decoded.dimension());
        assertEquals(new BlockPos(-1, 60, 8), decoded.pos1());
        assertEquals(new BlockPos(15, 79, 31), decoded.pos2());
        assertEquals(placementId, decoded.placementId());
    }

    @Test
    void regionSaveCarriesAnAbsentRegionIdForNewRegions()
    {
        LmpPayloads.RegionSavePayload decoded = roundTrip(new LmpPayloads.RegionSavePayload(null, "新建",
                "minecraft:overworld", new BlockPos(0, 60, 0), new BlockPos(15, 79, 15), null),
                LmpPayloads.RegionSavePayload.CODEC);

        assertNull(decoded.regionId(), "空区域 id 表示新建");
        assertNull(decoded.placementId(), "允许先划区后绑定");
    }

    @Test
    void regionDeleteSurvivesARoundTrip()
    {
        UUID regionId = UUID.randomUUID();
        LmpPayloads.RegionDeletePayload decoded = roundTrip(
                new LmpPayloads.RegionDeletePayload(regionId), LmpPayloads.RegionDeletePayload.CODEC);

        assertEquals(regionId, decoded.regionId());
    }

    @Test
    void everyPayloadHasItsOwnChannel()
    {
        // 同一个 id 注册两次会被 Fabric 拒绝，而复制粘贴新 payload 时最容易忘掉改 id
        List<Identifier> ids = List.of(
                LmpPayloads.RequestPayload.ID.id(),
                LmpPayloads.RegionListPayload.ID.id(),
                LmpPayloads.SnapshotPayload.ID.id(),
                LmpPayloads.StatusPayload.ID.id(),
                LmpPayloads.RegionSavePayload.ID.id(),
                LmpPayloads.RegionDeletePayload.ID.id());

        assertEquals(ids.size(), ids.stream().distinct().count(), "payload id 必须互不相同：" + ids);
        assertEquals(ids.size(), ids.stream().filter(id -> id.getNamespace().equals("lmp")).count(),
                "所有 payload 都必须在本模组自己的命名空间下：" + ids);
    }
}

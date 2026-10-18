package com.chenjicheng.lmp.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;

import com.chenjicheng.lmp.LmpMod;

import org.jetbrains.annotations.Nullable;

/**
 * 本模组自己的网络负载（自定义 payload）。
 *
 * <p>为什么不用 Syncmatica / Servux 的频道：Fabric 每个 payload id 只允许一个全局接收器
 * （重复注册会失败），而 Syncmatica 会把它自己命名空间下的任何 payload 强转为它的类型，
 * 因此复用别人的频道要么注册不上、要么直接 ClassCastException。这里使用独立命名空间 {@code lmp}。
 *
 * <p>载荷设计遵守「服务端权威、客户端只取列表」：客户端只发送「我要哪个区域、我手上是哪个版本」，
 * 服务端回区域列表、进度快照与扫描状态。
 */
public final class LmpPayloads
{
    /** 单个快照最多携带的条目数，防御异常数据。 */
    public static final int MAX_ENTRIES = 4096;

    private LmpPayloads()
    {
    }

    // ------------------------------------------------------------ 客户端 -> 服务端

    /**
     * 请求某个区域的进度快照。
     *
     * @param regionId      区域标识
     * @param knownRevision 客户端已有的版本号；服务端无论如何都会回包，客户端据此判断是否需要更新
     * @param forceRescan   玩家显式点了「刷新扫描」：忽略快照 TTL 直接重扫（仍受服务端限流约束）
     */
    public record RequestPayload(UUID regionId, long knownRevision, boolean forceRescan) implements CustomPayload
    {
        public static final CustomPayload.Id<RequestPayload> ID =
                new CustomPayload.Id<>(Identifier.of(LmpMod.MOD_ID, "request"));

        public static final PacketCodec<RegistryByteBuf, RequestPayload> CODEC = PacketCodec.of(
                (payload, buf) ->
                {
                    writeUuid(buf, payload.regionId());
                    buf.writeVarLong(payload.knownRevision());
                    buf.writeBoolean(payload.forceRescan());
                },
                buf -> new RequestPayload(readUuid(buf), buf.readVarLong(), buf.readBoolean()));

        public static final CustomPayload.Type<RegistryByteBuf, RequestPayload> TYPE =
                new CustomPayload.Type<>(ID, CODEC);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId()
        {
            return ID;
        }
    }

    // ------------------------------------------------------------ 服务端 -> 客户端

    /** 区域清单：玩家加入时以及任何区域变更后下发，客户端据此知道有哪些材料区。 */
    public record RegionListPayload(List<RegionInfo> regions) implements CustomPayload
    {
        public static final CustomPayload.Id<RegionListPayload> ID =
                new CustomPayload.Id<>(Identifier.of(LmpMod.MOD_ID, "region_list"));

        public static final PacketCodec<RegistryByteBuf, RegionListPayload> CODEC = PacketCodec.of(
                (payload, buf) ->
                {
                    List<RegionInfo> regions = payload.regions();
                    buf.writeVarInt(regions.size());

                    for (RegionInfo info : regions)
                    {
                        writeUuid(buf, info.id());
                        buf.writeString(info.name());
                        buf.writeString(info.dimension());
                        writeNullableUuid(buf, info.placementId());
                        buf.writeBlockPos(info.pos1());
                        buf.writeBlockPos(info.pos2());
                        buf.writeVarLong(info.revision());
                        buf.writeVarLong(info.lastScanAt());
                        buf.writeVarInt(info.coveragePermille());
                    }
                },
                buf ->
                {
                    int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
                    List<RegionInfo> regions = new ArrayList<>(size);

                    for (int i = 0; i < size; i++)
                    {
                        regions.add(new RegionInfo(
                                readUuid(buf),
                                buf.readString(),
                                buf.readString(),
                                readNullableUuid(buf),
                                buf.readBlockPos(),
                                buf.readBlockPos(),
                                buf.readVarLong(),
                                buf.readVarLong(),
                                buf.readVarInt()));
                    }

                    return new RegionListPayload(List.copyOf(regions));
                });

        public static final CustomPayload.Type<RegistryByteBuf, RegionListPayload> TYPE =
                new CustomPayload.Type<>(ID, CODEC);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId()
        {
            return ID;
        }
    }

    /**
     * 进度快照。物品用注册名传输（不用 raw id：raw id 依赖注册顺序与连接状态）。
     *
     * @param status 0=COMPLETE 1=PARTIAL 2=UNKNOWN_REQUIRED
     */
    public record SnapshotPayload(UUID regionId, long revision, byte status, int coveragePermille, List<Entry> entries)
            implements CustomPayload
    {
        public static final CustomPayload.Id<SnapshotPayload> ID =
                new CustomPayload.Id<>(Identifier.of(LmpMod.MOD_ID, "snapshot"));

        public static final PacketCodec<RegistryByteBuf, SnapshotPayload> CODEC = PacketCodec.of(
                (payload, buf) ->
                {
                    writeUuid(buf, payload.regionId());
                    buf.writeVarLong(payload.revision());
                    buf.writeByte(payload.status());
                    buf.writeVarInt(payload.coveragePermille());
                    buf.writeVarInt(payload.entries().size());

                    for (Entry entry : payload.entries())
                    {
                        buf.writeIdentifier(entry.itemId());
                        buf.writeVarInt(entry.required());
                        buf.writeVarLong(entry.present());
                    }
                },
                buf ->
                {
                    UUID regionId = readUuid(buf);
                    long revision = buf.readVarLong();
                    byte status = buf.readByte();
                    int coverage = buf.readVarInt();
                    int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
                    List<Entry> entries = new ArrayList<>(size);

                    for (int i = 0; i < size; i++)
                    {
                        entries.add(new Entry(buf.readIdentifier(), buf.readVarInt(), buf.readVarLong()));
                    }

                    return new SnapshotPayload(regionId, revision, status, coverage, List.copyOf(entries));
                });

        public static final CustomPayload.Type<RegistryByteBuf, SnapshotPayload> TYPE =
                new CustomPayload.Type<>(ID, CODEC);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId()
        {
            return ID;
        }
    }

    /**
     * 扫描进度（扫描过程中按节流频率下发）。
     *
     * @param phase 0=IDLE 1=SCANNING 2=FINALIZING
     */
    public record StatusPayload(UUID regionId, byte phase, long revision, int chunksDone, int chunksTotal,
                                int containersScanned) implements CustomPayload
    {
        public static final CustomPayload.Id<StatusPayload> ID =
                new CustomPayload.Id<>(Identifier.of(LmpMod.MOD_ID, "status"));

        public static final PacketCodec<RegistryByteBuf, StatusPayload> CODEC = PacketCodec.of(
                (payload, buf) ->
                {
                    writeUuid(buf, payload.regionId());
                    buf.writeByte(payload.phase());
                    buf.writeVarLong(payload.revision());
                    buf.writeVarInt(payload.chunksDone());
                    buf.writeVarInt(payload.chunksTotal());
                    buf.writeVarInt(payload.containersScanned());
                },
                buf -> new StatusPayload(readUuid(buf), buf.readByte(), buf.readVarLong(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        public static final CustomPayload.Type<RegistryByteBuf, StatusPayload> TYPE =
                new CustomPayload.Type<>(ID, CODEC);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId()
        {
            return ID;
        }
    }

    // ------------------------------------------------------------ 传输用的简单结构

    /**
     * 区域摘要（不含进度数据）。
     *
     * <p>带上两个角点是为了让客户端能回答「我现在站在哪个材料区里」以及显示体积——
     * 否则热键只能盲选，玩家得先记住区域名字。
     */
    public record RegionInfo(UUID id, String name, String dimension, @Nullable UUID placementId,
                             BlockPos pos1, BlockPos pos2, long revision, long lastScanAt, int coveragePermille)
    {
        /** 区域体积（格数），含两端。 */
        public long volume()
        {
            return (long) (this.pos2.getX() - this.pos1.getX() + 1)
                    * (this.pos2.getY() - this.pos1.getY() + 1)
                    * (this.pos2.getZ() - this.pos1.getZ() + 1);
        }

        public boolean contains(BlockPos pos)
        {
            return pos.getX() >= this.pos1.getX() && pos.getX() <= this.pos2.getX()
                    && pos.getY() >= this.pos1.getY() && pos.getY() <= this.pos2.getY()
                    && pos.getZ() >= this.pos1.getZ() && pos.getZ() <= this.pos2.getZ();
        }
    }

    /** 单个物品的所需与已收集数量。 */
    public record Entry(Identifier itemId, int required, long present)
    {
    }

    // ------------------------------------------------------------ 注册

    /**
     * 注册 payload 类型。必须在任何接收器注册之前调用，否则 Fabric 会因「类型未注册」拒绝。
     * 服务端与客户端都要调用（各自注册自己关心的方向即可，这里统一注册）。
     */
    public static void registerPayloadTypes()
    {
        PayloadTypeRegistry.playC2S().register(RequestPayload.ID, RequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RegionListPayload.ID, RegionListPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SnapshotPayload.ID, SnapshotPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StatusPayload.ID, StatusPayload.CODEC);
    }

    // ------------------------------------------------------------ 工具

    static void writeUuid(RegistryByteBuf buf, UUID uuid)
    {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    static UUID readUuid(RegistryByteBuf buf)
    {
        return new UUID(buf.readLong(), buf.readLong());
    }

    static void writeNullableUuid(RegistryByteBuf buf, @Nullable UUID uuid)
    {
        buf.writeBoolean(uuid != null);

        if (uuid != null)
        {
            writeUuid(buf, uuid);
        }
    }

    static @Nullable UUID readNullableUuid(RegistryByteBuf buf)
    {
        return buf.readBoolean() ? readUuid(buf) : null;
    }
}

package com.chenjicheng.lmp.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionStoreTest
{
    @BeforeAll
    static void bootstrapMinecraft()
    {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static MaterialRegion region(String name, BlockPos a, BlockPos b)
    {
        return new MaterialRegion(UUID.randomUUID(), name, Identifier.of("minecraft:overworld"),
                a, b, null, UUID.randomUUID(), 1000L, 0L, 0L, 0);
    }

    @Test
    @DisplayName("区域坐标自动归一化：先点哪个角都不影响")
    void coordinatesAreNormalized(@TempDir Path dir)
    {
        MaterialRegion region = region("warehouse", new BlockPos(30, 70, 30), new BlockPos(10, 60, 10));

        assertEquals(new BlockPos(10, 60, 10), region.pos1());
        assertEquals(new BlockPos(30, 70, 30), region.pos2());
        assertEquals(21L * 11L * 21L, region.volume());
    }

    @Test
    @DisplayName("体积/区块数超限会被拒绝（防 MSPT 的第一道闸）")
    void oversizedRegionsAreRejected()
    {
        MaterialRegion huge = region("huge", new BlockPos(0, 0, 0), new BlockPos(5000, 300, 5000));

        assertTrue(huge.validate().isPresent());
        assertEquals(MaterialRegion.Invalid.TOO_LARGE, huge.validate().orElseThrow());
    }

    @Test
    @DisplayName("写入后能读回：区域数据与版本号都要持久化")
    void persistenceRoundTrip(@TempDir Path dir)
    {
        Path file = RegionStore.fileFor(dir);
        RegionStore store = RegionStore.load(file);
        MaterialRegion saved = store.put(region("base", new BlockPos(0, 64, 0), new BlockPos(15, 80, 15)));
        store.put(region("second", new BlockPos(32, 64, 32), new BlockPos(47, 80, 47)));

        RegionStore reloaded = RegionStore.load(file);

        assertEquals(2, reloaded.all().size());
        MaterialRegion loaded = reloaded.byId(saved.id()).orElseThrow();
        assertEquals("base", loaded.name());
        assertEquals(saved.pos1(), loaded.pos1());
        assertEquals(saved.pos2(), loaded.pos2());
        assertEquals(saved.revision(), loaded.revision());
        assertEquals(store.revisionCounter(), reloaded.revisionCounter());
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")), "原子写不应残留临时文件");
    }

    @Test
    @DisplayName("任何变更都推进版本号：客户端据此判断快照过期")
    void mutationsBumpRevision(@TempDir Path dir)
    {
        RegionStore store = RegionStore.load(RegionStore.fileFor(dir));
        MaterialRegion first = store.put(region("a", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)));
        MaterialRegion second = store.put(region("b", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)));

        assertNotEquals(first.revision(), second.revision());

        long beforeRemove = store.revisionCounter();
        assertTrue(store.remove(first.id()));
        assertEquals(beforeRemove + 1L, store.revisionCounter());
        assertFalse(store.remove(first.id()), "重复删除应返回 false");
    }

    @Test
    @DisplayName("服务器启动时所有区域版本号推进：重启后客户端必须重新取一次")
    void serverStartInvalidatesCachedSnapshots(@TempDir Path dir)
    {
        RegionStore store = RegionStore.load(RegionStore.fileFor(dir));
        MaterialRegion region = store.put(region("a", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)));
        long revisionBefore = region.revision();

        store.onServerStart();

        long revisionAfter = store.byId(region.id()).orElseThrow().revision();
        assertTrue(revisionAfter > revisionBefore, "重启后版本号必须变化，否则客户端会渲染旧数据");
    }

    @Test
    @DisplayName("按 placement 查区域")
    void lookupByPlacement(@TempDir Path dir)
    {
        RegionStore store = RegionStore.load(RegionStore.fileFor(dir));
        UUID placementId = UUID.randomUUID();
        MaterialRegion base = region("a", new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        store.put(base.withPlacement(placementId));

        assertTrue(store.byPlacement(placementId).isPresent());
        assertEquals(base.id(), store.byPlacement(placementId).orElseThrow().id());
        assertTrue(store.byPlacement(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("文件损坏时降级为空存储，不抛异常")
    void corruptFileDegradesGracefully(@TempDir Path dir) throws IOException
    {
        Path file = RegionStore.fileFor(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        RegionStore store = RegionStore.load(file);

        assertTrue(store.all().isEmpty());
        assertNull(store.byId(UUID.randomUUID()).orElse(null));
    }

    @Test
    @DisplayName("区域覆盖的区块枚举：行优先且数量正确")
    void chunkEnumeration()
    {
        MaterialRegion region = region("a", new BlockPos(0, 0, 0), new BlockPos(31, 10, 15));

        assertEquals(2L, region.chunkCount());
        assertEquals(2, region.chunks().length);
        assertEquals(0, region.chunks()[0].getStartX());
        assertEquals(16, region.chunks()[1].getStartX());
        assertEquals(0, region.chunks()[1].getStartZ());
    }
}

package com.chenjicheng.lmp.client;

import java.util.List;
import java.util.UUID;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.net.LmpPayloads;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端缓存的更新规则测试。
 *
 * <p>这里守的是两条「乱序 / 幽灵数据」规则：迟到的旧快照不能覆盖新快照，服务端删掉的区域不能在
 * 客户端留下幽灵进度。两条都只在真实网络下才会碰到，因此必须在单测里钉住。
 *
 * <p>{@link ClientProgressStore} 是单例，测试之间必须 {@code reset()}，否则前一个用例的缓存会漏到后一个。
 */
class ClientProgressStoreTest
{
    private final UUID regionId = UUID.randomUUID();

    @BeforeAll
    static void bootstrapMinecraft()
    {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void resetStore()
    {
        ClientProgressStore.get().reset();
    }

    private void setRegions()
    {
        ClientProgressStore.get().setRegions(List.of(new LmpPayloads.RegionInfo(regionId, "仓库",
                "minecraft:overworld", null, new BlockPos(0, 0, 0), new BlockPos(15, 0, 15), 5L, 0L, 1000)));
    }

    @Test
    @DisplayName("乱序到达的旧快照不得覆盖新快照")
    void olderSnapshotDoesNotOverwriteTheNewerOne()
    {
        setRegions();
        ClientProgressStore store = ClientProgressStore.get();
        store.setProgress(regionId, 5L, (byte) 0, 1000, List.of(
                new LmpPayloads.Entry(net.minecraft.registry.Registries.ITEM.getId(Items.STONE), 100, 40L)));

        // 网络乱序：版本 3 的快照晚于版本 5 到达
        store.setProgress(regionId, 3L, (byte) 0, 1000, List.of(
                new LmpPayloads.Entry(net.minecraft.registry.Registries.ITEM.getId(Items.STONE), 100, 999L)));

        MaterialProgress progress = store.progress(regionId).orElseThrow();
        assertEquals(5L, progress.revision(), "版本号应当仍是新快照的");
        assertEquals(40L, progress.presentFor(Items.STONE), "旧快照的内容不得覆盖新快照");
    }

    @Test
    @DisplayName("同版本或更新的快照照常接受")
    void newerSnapshotIsAccepted()
    {
        setRegions();
        ClientProgressStore store = ClientProgressStore.get();
        store.setProgress(regionId, 5L, (byte) 0, 1000, List.of(
                new LmpPayloads.Entry(net.minecraft.registry.Registries.ITEM.getId(Items.STONE), 100, 40L)));
        store.setProgress(regionId, 6L, (byte) 0, 1000, List.of(
                new LmpPayloads.Entry(net.minecraft.registry.Registries.ITEM.getId(Items.STONE), 100, 60L)));

        assertEquals(60L, store.progress(regionId).orElseThrow().presentFor(Items.STONE));
    }

    @Test
    @DisplayName("服务端删掉的区域不得在客户端留下幽灵进度")
    void removingARegionClearsItsCachedProgress()
    {
        setRegions();
        ClientProgressStore store = ClientProgressStore.get();
        store.setProgress(regionId, 5L, (byte) 0, 1000, List.of());
        store.setStatus(regionId, (byte) 1, 5L, 3, 4, 10);
        assertTrue(store.progress(regionId).isPresent(), "前提：缓存里有这个区域的数据");

        store.setRegions(List.of());

        assertTrue(store.progress(regionId).isEmpty(), "区域被删除后不得再留下进度");
        assertTrue(store.status(regionId).isEmpty(), "区域被删除后不得再留下扫描状态");
        assertFalse(store.isScanning(regionId));
    }

    @Test
    @DisplayName("未知物品（模组被移除）不会让缓存崩掉，也不会被当成空气写进去")
    void unknownItemsAreSkipped()
    {
        setRegions();
        ClientProgressStore store = ClientProgressStore.get();
        store.setProgress(regionId, 5L, (byte) 0, 1000, List.of(
                new LmpPayloads.Entry(net.minecraft.util.Identifier.of("removed_mod:gone"), 10, 5L),
                new LmpPayloads.Entry(net.minecraft.registry.Registries.ITEM.getId(Items.STONE), 100, 40L)));

        MaterialProgress progress = store.progress(regionId).orElseThrow();
        assertEquals(1, progress.required().size(), "未知物品应当被跳过，实际=" + progress.required().keySet());
        assertEquals(40L, progress.presentFor(Items.STONE));
    }
}

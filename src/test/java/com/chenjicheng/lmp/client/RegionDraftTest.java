package com.chenjicheng.lmp.client;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.region.MaterialRegion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端「待保存的材料区」草稿的校验测试。
 *
 * <p>界面里的草稿要能在**发出请求之前**说清楚哪里不对，否则玩家只能靠服务端拒绝来试错。
 * 这里的判据与服务端共用 {@link MaterialRegion}，避免两处各写一套上限。
 */
class RegionDraftTest
{
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");

    private static RegionDraft draft(String name, BlockPos a, BlockPos b)
    {
        RegionDraft draft = new RegionDraft(UUID.randomUUID(), OVERWORLD);
        draft.setName(name);

        if (a != null)
        {
            draft.setCorners(a, b);
        }

        return draft;
    }

    @Test
    void acceptsACompleteDraft()
    {
        RegionDraft draft = draft("主基地仓库", new BlockPos(0, 60, 0), new BlockPos(15, 79, 15));

        assertTrue(draft.validate().isEmpty(), "完整草稿应当可以保存");
        assertTrue(draft.rangeSet());
        assertEquals(16L * 20L * 16L, draft.volume());
        assertEquals(1L, draft.chunkCount());
    }

    @Test
    void rejectsADraftWithoutARange()
    {
        RegionDraft draft = draft("主基地仓库", null, null);

        assertFalse(draft.rangeSet());
        Optional<MaterialRegion.Rejection> rejection = draft.validate();
        assertTrue(rejection.isPresent());
        assertEquals("lmp.error.region.no_range", rejection.get().messageKey());
    }

    @Test
    void rejectsABlankName()
    {
        RegionDraft draft = draft("   ", new BlockPos(0, 60, 0), new BlockPos(15, 79, 15));

        assertEquals("lmp.error.region.empty_name", draft.validate().orElseThrow().messageKey());
    }

    @Test
    void reportsTheSameChunkLimitAsTheServer()
    {
        RegionDraft draft = draft("太大", new BlockPos(0, 0, 0), new BlockPos(527, 1, 527));

        MaterialRegion.Rejection rejection = draft.validate().orElseThrow();
        assertEquals("lmp.error.region.too_many_chunks", rejection.messageKey());
        assertEquals(1, rejection.args().size());
    }

    @Test
    void normalizesCornersSoThePlayerMayFillThemInAnyOrder()
    {
        RegionDraft draft = draft("仓库", new BlockPos(15, 79, 15), new BlockPos(0, 60, 0));

        BlockPos[] corners = draft.corners().orElseThrow();
        assertEquals(new BlockPos(0, 60, 0), corners[0]);
        assertEquals(new BlockPos(15, 79, 15), corners[1]);
    }

    @Test
    void keepsThePlacementItWasOpenedFor()
    {
        UUID placement = UUID.randomUUID();
        RegionDraft draft = new RegionDraft(placement, OVERWORLD);

        assertEquals(placement, draft.placementId(), "草稿必须记住自己属于哪个共享原理图");
        assertEquals(OVERWORLD, draft.dimension());
    }

    @Test
    void trimsTheNameBeforeSaving()
    {
        RegionDraft draft = draft("  主基地仓库  ", new BlockPos(0, 60, 0), new BlockPos(15, 79, 15));

        assertEquals("主基地仓库", draft.trimmedName());
    }
}

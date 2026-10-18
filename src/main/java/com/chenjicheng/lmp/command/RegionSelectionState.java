package com.chenjicheng.lmp.command;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * 玩家划材料区时的「待定两个角点」。
 *
 * <p>只存在于服务端内存：玩家用 {@code /lmp region pos1|pos2} 依次定角，{@code create} 时才落盘。
 * 两个角必须在同一维度，否则拒绝（材料区属于某个维度）。
 */
public final class RegionSelectionState
{
    /** 待定选区。 */
    public record PendingSelection(Identifier dimension, @Nullable BlockPos pos1, @Nullable BlockPos pos2)
    {
        public boolean isComplete()
        {
            return this.pos1 != null && this.pos2 != null;
        }

        public PendingSelection withCorner(int corner, BlockPos pos)
        {
            return corner == 1
                    ? new PendingSelection(this.dimension, pos, this.pos2)
                    : new PendingSelection(this.dimension, this.pos1, pos);
        }
    }

    private final Map<UUID, PendingSelection> selections = new HashMap<>();

    /**
     * 记录一个角点。
     *
     * @return 失败原因（i18n 键后缀），成功返回空
     */
    public Optional<String> setCorner(UUID playerId, Identifier dimension, int corner, BlockPos pos)
    {
        PendingSelection current = this.selections.get(playerId);

        if (current != null && !current.dimension().equals(dimension))
        {
            // 换维度了：旧选区作废，重新开始，避免跨维度拼出一个奇怪的区域
            current = null;
        }

        PendingSelection base = current == null ? new PendingSelection(dimension, null, null) : current;
        this.selections.put(playerId, base.withCorner(corner, pos));
        return Optional.empty();
    }

    public Optional<PendingSelection> get(UUID playerId)
    {
        return Optional.ofNullable(this.selections.get(playerId));
    }

    /** 清空所有玩家的待定角点（服务器停止时调用）。 */
    public void clearAll()
    {
        this.selections.clear();
    }

    public void clear(UUID playerId)
    {
        this.selections.remove(playerId);
    }
}

package com.chenjicheng.lmp.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.LmpServer;
import com.chenjicheng.lmp.region.MaterialRegion;
import com.chenjicheng.lmp.region.RegionStore;
import com.chenjicheng.lmp.scan.ScanScheduler;

/**
 * {@code /lmp} 命令树：区域录入、扫描触发与状态查询。
 *
 * <p>区域录入以命令为唯一真相（GUI 只是把角点和名字拼成同样的命令发过来），这样：
 * 用 Litematica 选区工具、用视线取点、用坐标直填三种方式最终都落到同一套服务端校验上。
 *
 * <p>权限：默认要求权限等级 2（OP）。服务端的「谁能改材料区」是运维决策，
 * 这里选择一个不依赖额外权限模组的保守默认值。
 */
public final class LmpCommand
{
    /** 权限检查：1.21.11 的权限系统用 PermissionCheck 表达（GAMEMASTERS 对应旧权限等级 2）。 */
    private static final net.minecraft.command.permission.PermissionCheck REQUIRED_PERMISSION =
            CommandManager.GAMEMASTERS_CHECK;

    private LmpCommand()
    {
    }

    public static void register()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("lmp")
                        .requires(CommandManager.requirePermissionLevel(REQUIRED_PERMISSION))
                        .then(CommandManager.literal("region")
                                .then(CommandManager.literal("pos1")
                                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                                .executes(context -> setCorner(context, 1))))
                                .then(CommandManager.literal("pos2")
                                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                                .executes(context -> setCorner(context, 2))))
                                .then(CommandManager.literal("create")
                                        .then(CommandManager.argument("name", StringArgumentType.word())
                                                .executes(LmpCommand::create)))
                                .then(CommandManager.literal("bind")
                                        .then(CommandManager.argument("name", StringArgumentType.word())
                                                .then(CommandManager.argument("placement", StringArgumentType.word())
                                                        .executes(LmpCommand::bind))))
                                .then(CommandManager.literal("list").executes(LmpCommand::list))
                                .then(CommandManager.literal("info")
                                        .then(CommandManager.argument("name", StringArgumentType.word())
                                                .executes(LmpCommand::info)))
                                .then(CommandManager.literal("delete")
                                        .then(CommandManager.argument("name", StringArgumentType.word())
                                                .executes(LmpCommand::delete))))
                        .then(CommandManager.literal("scan")
                                .executes(context -> scan(context, null))
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> scan(context, StringArgumentType.getString(context, "name")))))
                        .then(CommandManager.literal("status").executes(LmpCommand::status))));
    }

    // ------------------------------------------------------------ region pos1/pos2

    private static int setCorner(CommandContext<ServerCommandSource> context, int corner)
    {
        ServerPlayerEntity player = context.getSource().getPlayer();

        if (player == null)
        {
            error(context, "lmp.command.player_only");
            return 0;
        }

        BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
        Identifier dimension = context.getSource().getWorld().getRegistryKey().getValue();
        LmpServer.selections().setCorner(player.getUuid(), dimension, corner, pos);

        context.getSource().sendFeedback(() -> Text.translatable(
                corner == 1 ? "lmp.command.region.pos1" : "lmp.command.region.pos2",
                pos.getX(), pos.getY(), pos.getZ()), false);
        return 1;
    }

    // ------------------------------------------------------------ region create

    private static int create(CommandContext<ServerCommandSource> context)
    {
        RegionStore store = LmpServer.store().orElse(null);

        if (store == null)
        {
            error(context, "lmp.command.not_ready");
            return 0;
        }

        ServerPlayerEntity player = context.getSource().getPlayer();

        if (player == null)
        {
            error(context, "lmp.command.player_only");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");

        if (findByName(store, name).isPresent())
        {
            error(context, "lmp.command.region.exists", name);
            return 0;
        }

        RegionSelectionState.PendingSelection selection = LmpServer.selections().get(player.getUuid()).orElse(null);

        if (selection == null || !selection.isComplete())
        {
            error(context, "lmp.command.region.missing_corners");
            return 0;
        }

        MaterialRegion region = new MaterialRegion(UUID.randomUUID(), name, selection.dimension(),
                selection.pos1(), selection.pos2(), null, player.getUuid(), System.currentTimeMillis(), 0L, 0L, 0);
        Optional<MaterialRegion.Invalid> invalid = region.validate();

        if (invalid.isPresent())
        {
            // 三个原因的 i18n 参数个数不同，必须分别给，不能一律塞 (体积, 区块数)
            switch (invalid.get())
            {
                case TOO_LARGE -> error(context, MaterialRegion.Invalid.TOO_LARGE.key(),
                        region.volume(), MaterialRegion.MAX_VOLUME);
                case TOO_MANY_CHUNKS -> error(context, MaterialRegion.Invalid.TOO_MANY_CHUNKS.key(),
                        region.chunkCount());
                case EMPTY_NAME -> error(context, MaterialRegion.Invalid.EMPTY_NAME.key());
            }

            return 0;
        }

        store.put(region);
        LmpServer.selections().clear(player.getUuid());
        LmpServer.onRegionsChanged();
        warnIfNotPersisted(context, store);

        context.getSource().sendFeedback(() -> Text.translatable("lmp.command.region.created", name,
                region.volume(), region.chunkCount()), true);
        return 1;
    }

    // ------------------------------------------------------------ region bind

    private static int bind(CommandContext<ServerCommandSource> context)
    {
        RegionStore store = LmpServer.store().orElse(null);

        if (store == null)
        {
            error(context, "lmp.command.not_ready");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        String placementRaw = StringArgumentType.getString(context, "placement");
        MaterialRegion region = findByName(store, name).orElse(null);

        if (region == null)
        {
            error(context, "lmp.command.region.not_found", name);
            return 0;
        }

        UUID placementId;

        try
        {
            placementId = UUID.fromString(placementRaw);
        }
        catch (IllegalArgumentException e)
        {
            error(context, "lmp.command.region.bad_placement", placementRaw);
            return 0;
        }

        store.put(region.withPlacement(placementId));
        LmpServer.onRegionsChanged();
        warnIfNotPersisted(context, store);
        context.getSource().sendFeedback(() -> Text.translatable("lmp.command.region.bound", name,
                placementId.toString()), true);
        return 1;
    }

    // ------------------------------------------------------------ region list/info/delete

    private static int list(CommandContext<ServerCommandSource> context)
    {
        RegionStore store = LmpServer.store().orElse(null);

        if (store == null)
        {
            error(context, "lmp.command.not_ready");
            return 0;
        }

        List<MaterialRegion> regions = List.copyOf(store.all());

        if (regions.isEmpty())
        {
            context.getSource().sendFeedback(() -> Text.translatable("lmp.command.region.list_empty"), false);
            return 1;
        }

        for (MaterialRegion region : regions)
        {
            context.getSource().sendFeedback(() -> Text.translatable("lmp.command.region.list_entry",
                    region.name(),
                    region.dimension().toString(),
                    region.volume(),
                    region.coveragePermille(),
                    region.placementId() == null ? "-" : region.placementId().toString()), false);
        }

        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> context)
    {
        RegionStore store = LmpServer.store().orElse(null);

        if (store == null)
        {
            error(context, "lmp.command.not_ready");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        MaterialRegion region = findByName(store, name).orElse(null);

        if (region == null)
        {
            error(context, "lmp.command.region.not_found", name);
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.translatable("lmp.command.region.info",
                region.name(),
                region.dimension().toString(),
                region.pos1().getX(), region.pos1().getY(), region.pos1().getZ(),
                region.pos2().getX(), region.pos2().getY(), region.pos2().getZ(),
                region.volume(), region.chunkCount(), region.revision(), region.coveragePermille()), false);
        return 1;
    }

    private static int delete(CommandContext<ServerCommandSource> context)
    {
        RegionStore store = LmpServer.store().orElse(null);

        if (store == null)
        {
            error(context, "lmp.command.not_ready");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        MaterialRegion region = findByName(store, name).orElse(null);

        if (region == null)
        {
            error(context, "lmp.command.region.not_found", name);
            return 0;
        }

        // 只取消这个区域自己的扫描：取消全部会顺带打断另一个无关区域的扫描
        LmpServer.scheduler().ifPresent(scheduler -> scheduler.cancelRegion(region.id()));
        store.remove(region.id());
        LmpServer.onRegionsChanged();
        warnIfNotPersisted(context, store);
        context.getSource().sendFeedback(() -> Text.translatable("lmp.command.region.deleted", name), true);
        return 1;
    }

    // ------------------------------------------------------------ scan / status

    private static int scan(CommandContext<ServerCommandSource> context, String name)
    {
        RegionStore store = LmpServer.store().orElse(null);
        ScanScheduler scheduler = LmpServer.scheduler().orElse(null);

        if (store == null || scheduler == null)
        {
            error(context, "lmp.command.not_ready");
            return 0;
        }

        MaterialRegion region;

        if (name != null)
        {
            region = findByName(store, name).orElse(null);

            if (region == null)
            {
                error(context, "lmp.command.region.not_found", name);
                return 0;
            }
        }
        else
        {
            Identifier dimension = context.getSource().getWorld().getRegistryKey().getValue();
            List<MaterialRegion> candidates = store.inDimension(dimension);

            if (candidates.size() != 1)
            {
                error(context, "lmp.command.scan.need_name", candidates.size());
                return 0;
            }

            region = candidates.get(0);
        }

        ServerPlayerEntity player = context.getSource().getPlayer();
        UUID playerId = player == null ? null : player.getUuid();
        Optional<String> failure = scheduler.requestScan(region, playerId);

        if (failure.isPresent())
        {
            error(context, "lmp.command.scan." + failure.get());
            return 0;
        }

        MaterialRegion target = region;
        context.getSource().sendFeedback(() -> Text.translatable("lmp.command.scan.started", target.name()), true);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context)
    {
        ScanScheduler scheduler = LmpServer.scheduler().orElse(null);

        if (scheduler == null)
        {
            error(context, "lmp.command.not_ready");
            return 0;
        }

        boolean any = false;

        for (MaterialRegion region : LmpServer.store().map(RegionStore::all).orElse(List.of()))
        {
            ScanScheduler.ScanStatus scanStatus = scheduler.status(region.id()).orElse(null);

            if (scanStatus == null)
            {
                continue;
            }

            any = true;
            context.getSource().sendFeedback(() -> Text.translatable("lmp.command.status.entry",
                    region.name(), scanStatus.phase().name(), scanStatus.chunksDone(), scanStatus.chunksTotal(),
                    scanStatus.containersScanned()), false);
        }

        if (!any)
        {
            context.getSource().sendFeedback(() -> Text.translatable("lmp.command.status.idle"), false);
        }

        return 1;
    }

    // ------------------------------------------------------------ 工具

    private static Optional<MaterialRegion> findByName(RegionStore store, String name)
    {
        String needle = name.toLowerCase(Locale.ROOT);
        return store.all().stream()
                .filter(region -> region.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }

    /**
     * 写盘失败时明确告知玩家。
     *
     * <p>内存里的改动是成功的，所以命令本身不算失败；但重启之后它会消失，
     * 只留一条服务端日志的话，玩家会以为已经存住了。
     */
    private static void warnIfNotPersisted(CommandContext<ServerCommandSource> context, RegionStore store)
    {
        if (!store.lastSaveSucceeded())
        {
            context.getSource().sendError(Text.translatable("lmp.command.region.save_failed"));
        }
    }

    private static void error(CommandContext<ServerCommandSource> context, String key, Object... args)
    {
        context.getSource().sendError(Text.translatable(key, args));
    }
}

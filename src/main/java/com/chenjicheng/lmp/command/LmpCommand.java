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

import com.chenjicheng.lmp.LmpServer;
import com.chenjicheng.lmp.region.MaterialRegion;
import com.chenjicheng.lmp.region.RegionEditor;
import com.chenjicheng.lmp.region.RegionStore;

/**
 * {@code /lmp} 命令树：区域录入、扫描触发与状态查询。
 *
 * <p><b>这不是玩家的主要入口。</b>玩家在共享原理图的放置列表里点按钮，走界面与 payload；
 * 这里保留命令是为了控制台、数据包与排障——专用服务器上没有界面，但运维仍需要能看一眼、
 * 改一下区域。两条入口共用 {@link RegionEditor}，因此校验、权限、限流与拒绝理由完全一致。
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
                                // 角点直接写在参数里：不再有「先 pos1 再 pos2 最后 create」的待定状态。
                                // 界面的保存请求自带两个角点，服务端不需要跨命令保存临时状态。
                                .then(CommandManager.literal("create")
                                        .then(CommandManager.argument("name", StringArgumentType.string())
                                                .then(CommandManager.argument("pos1", BlockPosArgumentType.blockPos())
                                                        .then(CommandManager.argument("pos2", BlockPosArgumentType.blockPos())
                                                                .executes(LmpCommand::create)))))
                                .then(CommandManager.literal("bind")
                                        .then(CommandManager.argument("name", StringArgumentType.string())
                                                .then(CommandManager.argument("placement", StringArgumentType.word())
                                                        .executes(LmpCommand::bind))))
                                .then(CommandManager.literal("list").executes(LmpCommand::list))
                                .then(CommandManager.literal("info")
                                        .then(CommandManager.argument("name", StringArgumentType.string())
                                                .executes(LmpCommand::info)))
                                .then(CommandManager.literal("delete")
                                        .then(CommandManager.argument("name", StringArgumentType.string())
                                                .executes(LmpCommand::delete))))
                        .then(CommandManager.literal("scan")
                                .executes(context -> scan(context, null))
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .executes(context -> scan(context, StringArgumentType.getString(context, "name")))))
                        .then(CommandManager.literal("status").executes(LmpCommand::status))));
    }

    // ------------------------------------------------------------ region create / bind

    /** 名字已存在就更新那一条，否则新建；因此控制台不需要区分 create 与 edit 两个动词。 */
    private static int create(CommandContext<ServerCommandSource> context)
    {
        RegionEditor editor = LmpServer.editor().orElse(null);

        if (editor == null)
        {
            error(context, "lmp.msg.not_ready");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        BlockPos pos1 = BlockPosArgumentType.getBlockPos(context, "pos1");
        BlockPos pos2 = BlockPosArgumentType.getBlockPos(context, "pos2");
        Identifier dimension = context.getSource().getWorld().getRegistryKey().getValue();

        RegionEditor.Result result = editor.saveByName(name, dimension, pos1, pos2, null, actorId(context));
        int outcome = report(context, result);

        if (result.ok())
        {
            LmpServer.onRegionsChanged();
        }

        return outcome;
    }

    private static int bind(CommandContext<ServerCommandSource> context)
    {
        RegionStore store = LmpServer.store().orElse(null);
        RegionEditor editor = LmpServer.editor().orElse(null);

        if (store == null || editor == null)
        {
            error(context, "lmp.msg.not_ready");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        String placementRaw = StringArgumentType.getString(context, "placement");
        MaterialRegion region = findByName(store, name).orElse(null);

        if (region == null)
        {
            error(context, "lmp.msg.region.not_found", name);
            return 0;
        }

        UUID placementId;

        try
        {
            placementId = UUID.fromString(placementRaw);
        }
        catch (IllegalArgumentException e)
        {
            error(context, "lmp.msg.region.bad_placement", placementRaw);
            return 0;
        }

        // 走 save 而不是直接改字段：绑定唯一性、名称与体积校验都在同一处判定
        RegionEditor.Result result = editor.save(region.id(), region.name(), region.dimension(),
                region.pos1(), region.pos2(), placementId, null);
        int outcome = report(context, result);

        if (result.ok())
        {
            LmpServer.onRegionsChanged();
        }

        return outcome;
    }

    // ------------------------------------------------------------ region list/info/delete

    private static int list(CommandContext<ServerCommandSource> context)
    {
        RegionStore store = LmpServer.store().orElse(null);

        if (store == null)
        {
            error(context, "lmp.msg.not_ready");
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
            error(context, "lmp.msg.not_ready");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        MaterialRegion region = findByName(store, name).orElse(null);

        if (region == null)
        {
            error(context, "lmp.msg.region.not_found", name);
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
        RegionEditor editor = LmpServer.editor().orElse(null);

        if (store == null || editor == null)
        {
            error(context, "lmp.msg.not_ready");
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        MaterialRegion region = findByName(store, name).orElse(null);

        if (region == null)
        {
            error(context, "lmp.msg.region.not_found", name);
            return 0;
        }

        RegionEditor.Result result = editor.delete(region.id());
        int outcome = report(context, result);

        if (result.ok())
        {
            LmpServer.onRegionsChanged();
        }

        return outcome;
    }

    // ------------------------------------------------------------ scan / status

    private static int scan(CommandContext<ServerCommandSource> context, String name)
    {
        RegionStore store = LmpServer.store().orElse(null);
        RegionEditor editor = LmpServer.editor().orElse(null);

        if (store == null || editor == null)
        {
            error(context, "lmp.msg.not_ready");
            return 0;
        }

        MaterialRegion region;

        if (name != null)
        {
            region = findByName(store, name).orElse(null);

            if (region == null)
            {
                error(context, "lmp.msg.region.not_found", name);
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

        return report(context, editor.rescan(region.id(), actorId(context)));
    }

    private static int status(CommandContext<ServerCommandSource> context)
    {
        if (LmpServer.scheduler().isEmpty())
        {
            error(context, "lmp.msg.not_ready");
            return 0;
        }

        boolean any = false;

        for (MaterialRegion region : LmpServer.store().map(RegionStore::all).orElse(List.of()))
        {
            var scanStatus = LmpServer.scheduler().flatMap(scheduler -> scheduler.status(region.id())).orElse(null);

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

    private static UUID actorId(CommandContext<ServerCommandSource> context)
    {
        ServerPlayerEntity player = context.getSource().getPlayer();
        return player == null ? null : player.getUuid();
    }

    /**
     * 把编辑结果渲染成反馈。
     *
     * <p>失败返回 0（命令确实没成功）；成功时若服务端没能落盘，再补一条明确的提醒——
     * 内存里的改动是成功的，重启之后却会消失。
     */
    private static int report(CommandContext<ServerCommandSource> context, RegionEditor.Result result)
    {
        if (!result.ok())
        {
            context.getSource().sendError(Text.translatable(result.messageKey(), result.argsArray()));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.translatable(result.messageKey(), result.argsArray()), true);

        if (result.warningKey() != null)
        {
            context.getSource().sendError(Text.translatable(result.warningKey()));
        }

        return 1;
    }

    private static void error(CommandContext<ServerCommandSource> context, String key, Object... args)
    {
        context.getSource().sendError(Text.translatable(key, args));
    }
}

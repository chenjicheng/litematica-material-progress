package com.chenjicheng.lmp.client;

import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import com.chenjicheng.lmp.client.gui.LmpSelectionBridge;
import com.chenjicheng.lmp.material.MaterialProgress;
import com.chenjicheng.lmp.net.LmpClientNetworking;
import com.chenjicheng.lmp.net.LmpPayloads;

/**
 * 客户端辅助命令 {@code /lmpc}。
 *
 * <p>它<b>不</b>自己修改任何状态：所有落库动作都翻译成服务端命令
 * （{@code /lmp region pos1|pos2|create|bind}）再发出去，因此服务端校验、权限、限流一条都不少。
 * 这里解决的只是「玩家不方便手打坐标和 UUID」。
 *
 * <p>命令名带后缀 {@code c} 是刻意的：连接服务器时服务端命令会覆盖同名客户端命令，
 * 而 {@code /lmp} 是服务端命令树，两者不能同名。
 */
public final class LmpClientCommands
{
    private static final String LITEMATICA = "litematica";
    private static final String SYNCMATICA = "syncmatica";

    private LmpClientCommands()
    {
    }

    public static void register()
    {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("lmpc")
                        .then(ClientCommandManager.literal("gui")
                                .executes(context -> gui(context, null))
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> gui(context, StringArgumentType.getString(context, "name")))))
                        .then(ClientCommandManager.literal("pos1").executes(context -> corner(context, 1)))
                        .then(ClientCommandManager.literal("pos2").executes(context -> corner(context, 2)))
                        .then(ClientCommandManager.literal("create")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .executes(LmpClientCommands::create)))
                        .then(ClientCommandManager.literal("from-selection")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .executes(LmpClientCommands::fromSelection)))
                        .then(ClientCommandManager.literal("bind")
                                .then(ClientCommandManager.argument("region", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("placement", StringArgumentType.word())
                                                .executes(LmpClientCommands::bind))))
                        .then(ClientCommandManager.literal("placements").executes(LmpClientCommands::placements))
                        .then(ClientCommandManager.literal("refresh")
                                .executes(context -> refresh(context, null))
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> refresh(context, StringArgumentType.getString(context, "name")))))));
    }

    // ------------------------------------------------------------ 界面

    private static int gui(CommandContext<FabricClientCommandSource> context, String name)
    {
        if (name == null)
        {
            LmpClientEntry.openDefault(context.getSource().getClient());
            return 1;
        }

        Optional<LmpPayloads.RegionInfo> region = LmpClientEntry.byName(name);

        if (region.isEmpty())
        {
            error(context, Text.translatable("lmp.command.region.not_found", name));
            return 0;
        }

        LmpClientEntry.open(region.get());
        return 1;
    }

    private static int refresh(CommandContext<FabricClientCommandSource> context, String name)
    {
        Optional<LmpPayloads.RegionInfo> region;

        if (name == null)
        {
            ClientProgressStore store = ClientProgressStore.get();
            List<LmpPayloads.RegionInfo> regions = store.regions();

            if (regions.isEmpty())
            {
                error(context, Text.translatable("lmp.gui.status.region_empty"));
                return 0;
            }

            if (regions.size() > 1)
            {
                error(context, Text.translatable("lmp.command.scan.need_name", regions.size()));
                return 0;
            }

            region = Optional.of(regions.get(0));
        }
        else
        {
            region = LmpClientEntry.byName(name);
        }

        if (region.isEmpty())
        {
            error(context, Text.translatable("lmp.command.region.not_found", name));
            return 0;
        }

        long revision = ClientProgressStore.get().progress(region.get().id())
                .map(MaterialProgress::revision)
                .orElse(0L);
        LmpClientNetworking.requestSnapshot(region.get().id(), revision, true);
        feedback(context, Text.translatable("lmp.command.scan.started", region.get().name()));
        return 1;
    }

    // ------------------------------------------------------------ 区域录入

    private static int corner(CommandContext<FabricClientCommandSource> context, int corner)
    {
        MinecraftClient client = context.getSource().getClient();
        HitResult hit = client.crosshairTarget;

        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit))
        {
            error(context, Text.translatable("lmp.command.region.no_target"));
            return 0;
        }

        BlockPos pos = blockHit.getBlockPos();

        if (!forward(context, "lmp region pos%d %d %d %d".formatted(corner, pos.getX(), pos.getY(), pos.getZ())))
        {
            return 0;
        }

        feedback(context, Text.translatable("lmp.command.region.sent_corner", corner,
                pos.getX(), pos.getY(), pos.getZ()));
        return 1;
    }

    private static int create(CommandContext<FabricClientCommandSource> context)
    {
        String name = StringArgumentType.getString(context, "name");
        return forward(context, "lmp region create " + name) ? 1 : 0;
    }

    private static int fromSelection(CommandContext<FabricClientCommandSource> context)
    {
        if (!FabricLoader.getInstance().isModLoaded(LITEMATICA))
        {
            error(context, Text.translatable("lmp.command.region.no_litematica"));
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        Optional<BlockPos[]> bounds = LmpSelectionBridge.currentBounds();

        if (bounds.isEmpty())
        {
            error(context, Text.translatable("lmp.command.region.no_selection"));
            return 0;
        }

        BlockPos min = bounds.get()[0];
        BlockPos max = bounds.get()[1];

        // 三条命令按顺序发；服务端按连接顺序处理，因此 create 时角点已经就位
        if (!forward(context, "lmp region pos1 %d %d %d".formatted(min.getX(), min.getY(), min.getZ()))
                || !forward(context, "lmp region pos2 %d %d %d".formatted(max.getX(), max.getY(), max.getZ()))
                || !forward(context, "lmp region create " + name))
        {
            return 0;
        }

        feedback(context, Text.translatable("lmp.command.region.sent_selection", name));
        return 1;
    }

    private static int bind(CommandContext<FabricClientCommandSource> context)
    {
        String region = StringArgumentType.getString(context, "region");
        String placement = StringArgumentType.getString(context, "placement");
        return forward(context, "lmp region bind " + region + " " + placement) ? 1 : 0;
    }

    // ------------------------------------------------------------ 共享放置清单

    private static int placements(CommandContext<FabricClientCommandSource> context)
    {
        if (!FabricLoader.getInstance().isModLoaded(SYNCMATICA))
        {
            error(context, Text.translatable("lmp.command.region.no_syncmatica"));
            return 0;
        }

        List<LmpSyncmaticaBridge.Placement> placements = LmpSyncmaticaBridge.placements();

        if (placements.isEmpty())
        {
            feedback(context, Text.translatable("lmp.command.region.placements_empty"));
            return 1;
        }

        feedback(context, Text.translatable("lmp.command.region.placements_header"));

        for (LmpSyncmaticaBridge.Placement placement : placements)
        {
            // 点一下就把 bind 命令填进输入框，UUID 不用手抄
            MutableText line = Text.literal("- " + placement.name() + "  ")
                    .append(Text.literal(placement.dimension() + "  ").formatted(Formatting.GRAY))
                    .append(Text.literal(placement.id()).formatted(Formatting.DARK_GRAY))
                    .styled(style -> style.withClickEvent(
                            new ClickEvent.SuggestCommand("/lmpc bind <材料区> " + placement.id())));
            feedback(context, line);
        }

        return 1;
    }

    // ------------------------------------------------------------ 工具

    /**
     * 把动作转发成服务端命令。
     *
     * @return 是否真的发出去了；未连接时返回 false 并给出明确报错，而不是静默失败
     */
    private static boolean forward(CommandContext<FabricClientCommandSource> context, String command)
    {
        MinecraftClient client = context.getSource().getClient();

        if (client.player == null || client.player.networkHandler == null)
        {
            error(context, Text.translatable("lmp.command.not_connected"));
            return false;
        }

        client.player.networkHandler.sendChatCommand(command);
        return true;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> context, Text text)
    {
        context.getSource().sendFeedback(text);
    }

    private static void error(CommandContext<FabricClientCommandSource> context, Text text)
    {
        context.getSource().sendError(text);
    }
}

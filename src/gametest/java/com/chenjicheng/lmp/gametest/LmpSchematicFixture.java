package com.chenjicheng.lmp.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.data.ServerPlacement;
import ch.endte.syncmatica.extended_core.PlayerIdentifier;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.tag.util.DataFileUtils;

/**
 * 生成一份**真实**的 {@code .litematic}，并把它登记成 Syncmatica 的共享放置。
 *
 * <p>为什么不用手写 NBT：手写的数据只能证明「我们的解析器读得懂我们自己写的东西」。
 * 这里让 Litematica 自己把世界里的区域序列化出来（{@code createFromWorld} + {@code writeToNBT}），
 * 于是服务端解析的是**上游写出的文件**——这正好补上 docs/design.md 记了很久的那个缺口
 * （「未与真实原理图对拍」）。它不是从别人那里导出的成品，但格式由上游自己的写入器产生。
 *
 * <p>本类位于独立的 gametest 源集，不进入发布产物。
 */
public final class LmpSchematicFixture
{
    private LmpSchematicFixture()
    {
    }

    /**
     * 用 Litematica 自己的序列化器把一个世界区域写成原理图对象。
     *
     * @return 原理图；区域为空或上游拒绝时返回 {@code null}
     */
    public static LitematicaSchematic capture(World world, BlockPos min, BlockPos max, String name)
    {
        AreaSelection selection = new AreaSelection();
        selection.setName(name);

        String boxName = selection.createNewSubRegionBox(min, "main");
        Box box = selection.getSubRegionBox(boxName);
        selection.setSubRegionCornerPos(box, PositionUtils.Corner.CORNER_1, min);
        selection.setSubRegionCornerPos(box, PositionUtils.Corner.CORNER_2, max);
        selection.setExplicitOrigin(min);

        // visibleOnly = false：只要露在外面的方块会让「所需材料」随摆放方式变化，测试就不可复现
        return LitematicaSchematic.createFromWorld(world, selection,
                new LitematicaSchematic.SchematicSaveInfo(false, false), name, message -> { });
    }

    /** 把原理图写成一个文件（用上游的写入器，不是我们拼的 NBT）。 */
    public static void write(LitematicaSchematic schematic, Path file) throws IOException
    {
        Path parent = file.getParent();

        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        // 用上游自己的写入器（与它保存文件时走同一条路径），不是我们拼的 NBT
        if (!DataFileUtils.writeCompoundDataToCompressedNbtFile(file, schematic.writeToData()))
        {
            throw new IOException("写入测试用原理图失败：" + file);
        }
    }

    /**
     * 在给定上下文里登记一个共享放置：文件写到该上下文自己的原理图目录下，名字是内容哈希。
     *
     * <p>服务端与客户端各有一份上下文、各自的目录，因此两侧都要登记一次；
     * 服务端靠 {@code SyncmaticManager.getPlacement(id).getHash()} 找到文件（见 SchematicLocator），
     * 客户端靠同一个哈希从自己的目录里取文件来渲染。
     *
     * @return 登记好的放置
     */
    public static ServerPlacement share(Context context, LitematicaSchematic schematic, UUID placementId,
                                        String dimension, BlockPos origin) throws IOException
    {
        Path folder = context.getLitematicFolder();
        Path temp = Files.createTempFile("lmp-schematic", ".litematic");
        write(schematic, temp);

        // 哈希必须由 Syncmatica 自己按**文件内容**算：它用这个哈希校验本地副本是否同步，
        // 随便给一个 UUID 会被判成 LOCAL_LITEMATIC_DESYNC，渲染直接失败。
        ServerPlacement placement = new ServerPlacement(placementId, temp, "lmp-test.litematic",
                PlayerIdentifier.MISSING_PLAYER);
        UUID hash = placement.getHash();

        // 文件名必须是 <哈希>.litematic：服务端靠它定位原理图（见 SchematicLocator），客户端也一样
        Files.createDirectories(folder);
        Path file = folder.resolve(hash + ".litematic");
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        placement.setFile(file);
        placement.move(dimension, origin, BlockRotation.NONE, BlockMirror.NONE);
        context.getSyncmaticManager().addPlacement(placement);

        return placement;
    }
}

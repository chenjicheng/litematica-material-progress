package com.chenjicheng.lmp.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.gen.WorldPresets;

/**
 * 客户端用例共用的世界创建方式：**超平坦 + 开作弊**。
 *
 * <p>超平坦不是为了省事：框架加载世界有 60 秒的硬编码上限
 * （{@code ClientGameTestImpl.waitForWorldLoad}），而托管 runner 是 2 核、客户端又走 Mesa 软件渲染，
 * 默认地形生成会在「Preparing spawn area: 16%」卡上一分多钟，用例因此超时——失败点与断言无关。
 * 这些用例不依赖地形（材料区是自己摆的），换成超平坦后世界准备基本是瞬时的。
 *
 * <p>作弊开关是给需要写材料区的用例用的：改材料区要求权限等级 2，未开作弊的单人世界主机是 0。
 */
final class LmpTestWorlds
{
    private LmpTestWorlds()
    {
    }

    static TestSingleplayerContext create(ClientGameTestContext context)
    {
        return context.worldBuilder()
                .adjustSettings(creator ->
                {
                    creator.setCheatsEnabled(true);
                    creator.getNormalWorldTypes().stream()
                            .filter(type -> type.preset().matchesKey(WorldPresets.FLAT))
                            .findFirst()
                            .ifPresent(creator::setWorldType);
                })
                .create();
    }
}

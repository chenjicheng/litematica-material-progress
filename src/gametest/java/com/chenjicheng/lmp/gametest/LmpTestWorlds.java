package com.chenjicheng.lmp.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.world.gen.WorldPresets;

/**
 * 客户端用例共用的世界创建方式：**压低渲染开销 + 超平坦 + 开作弊**。
 *
 * <p>这些设置不是为了省事。托管 runner 只有 2 核，客户端又走 Mesa 的软件渲染；默认设置下
 * 渲染线程会把集成服务端饿死（日志里反复出现 {@code Can't keep up! Running 2002ms behind}），
 * 世界准备因此超过框架 60 秒的硬编码上限（{@code ClientGameTestImpl.waitForWorldLoad}），
 * 用例以 {@code Timeout loading world} 失败——失败点与断言无关。
 *
 * <p>因此先在主菜单把视距、粒子、云、光影都调到最低，再创建世界。用例不依赖画面效果：
 * 结构是自己摆的，断言读的是游戏状态与截图存档。
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
        context.runOnClient(client ->
        {
            client.options.getViewDistance().setValue(2);
            client.options.getSimulationDistance().setValue(5);
            client.options.getMaxFps().setValue(30);
            client.options.getParticles().setValue(ParticlesMode.MINIMAL);
            client.options.getCloudRenderMode().setValue(CloudRenderMode.OFF);
            client.options.getAo().setValue(false);
            client.options.getEntityShadows().setValue(false);
        });

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

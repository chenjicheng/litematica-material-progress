package com.chenjicheng.lmp.client;

import net.fabricmc.api.ClientModInitializer;

import com.chenjicheng.lmp.LmpMod;
import com.chenjicheng.lmp.net.LmpClientNetworking;

/**
 * 客户端入口：只做展示与取数，从不扫描。
 *
 * <p>客户端不注册任何世界相关事件；扫描完全在服务端进行，客户端拿到的是服务端算好的结果列表。
 */
public class LmpClient implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        LmpClientNetworking.register();
        LmpClientCommands.register();
        LmpKeybinds.register();
        LmpMod.LOGGER.info("{} 客户端组件已注册", LmpMod.MOD_ID);
    }
}

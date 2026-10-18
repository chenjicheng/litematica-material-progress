package com.chenjicheng.lmp.client;

import com.chenjicheng.lmp.LmpMod;
import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端入口：只做展示与取数，不做扫描。
 *
 * <p>M1：注册 S2C 接收器、按 placementId 请求快照、渲染最小表格。
 * M3：改为 malilib 的 GuiListBase 界面，并复用 Litematica 的 GuiMaterialList。
 */
public class LmpClient implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        LmpMod.LOGGER.info("{} 客户端初始化（骨架，尚未实现功能）", LmpMod.MOD_ID);
    }
}

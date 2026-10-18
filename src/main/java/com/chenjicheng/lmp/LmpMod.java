package com.chenjicheng.lmp;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用（服务端 + 客户端）入口。
 *
 * <p>服务端侧的全部装配都在 {@link LmpServer} 里：材料区存储、异步分片扫描、网络通道与命令。
 * 本类不得引用任何客户端专属类，否则专用服务器启动会失败。
 */
public class LmpMod implements ModInitializer
{
    public static final String MOD_ID = "lmp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize()
    {
        LmpServer.initialize();
    }
}

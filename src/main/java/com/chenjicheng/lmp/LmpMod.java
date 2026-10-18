package com.chenjicheng.lmp;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用（服务端 + 客户端）入口。
 *
 * <p>本入口负责服务端权威的部分：材料区存储、异步分片扫描、所需材料清单与网络通道。
 * 它不得引用任何客户端专属类，否则专用服务器启动会失败。
 */
public class LmpMod implements ModInitializer
{
    public static final String MOD_ID = "lmp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize()
    {
        // M1：注册 payload 类型（先于接收器）、ServerTickEvents 扫描钩子与 /lmp 命令。
        LOGGER.info("{} 初始化（骨架，尚未实现功能）", MOD_ID);
    }
}

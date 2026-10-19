package com.chenjicheng.lmp.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

import com.chenjicheng.lmp.LmpMod;

/**
 * 热键入口。
 *
 * <p>默认 {@code K}：与 Litematica 的默认热键（M 主菜单、I 信息叠加）以及原版都不冲突。
 * 冲突时玩家可以在原版按键设置里改——分类独立成 {@code key.category.lmp.main}，不会混进「杂项」。
 */
public final class LmpKeybinds
{
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(LmpMod.MOD_ID, "main"));

    private static KeyBinding openProgress;

    private LmpKeybinds()
    {
    }

    public static void register()
    {
        // 用按键名而不是 GLFW 常量：少一个平台相关的依赖，读起来也更直观
        int defaultKey = InputUtil.fromTranslationKey("key.keyboard.k").getCode();

        openProgress = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lmp.open_progress", InputUtil.Type.KEYSYM, defaultKey, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            // 必须把按键队列排空：一 tick 内多次按下时逐个处理，避免把按下事件留在队列里
            while (openProgress.wasPressed())
            {
                if (client.currentScreen == null)
                {
                    LmpClientEntry.openDefault(client);
                }
            }
        });
    }
}

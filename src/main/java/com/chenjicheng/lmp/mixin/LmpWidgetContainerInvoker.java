package com.chenjicheng.lmp.mixin;

import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把 malilib 的 {@code WidgetContainer.addButton} 暴露出来。
 *
 * <p>为什么不让自己那个 mixin 直接继承 {@code WidgetListEntryBase} 再调 {@code this.addButton(...)}：
 * 那需要 mixin 声明一个与目标构造函数对不上的「代理构造函数」，而一旦签名对上了，Mixin 会把它
 * 合并成目标类的一个新构造函数。用 {@code @Invoker} 生成公开桥接方法没有这个风险，
 * 也不需要让 mixin 类去模仿上游的继承结构。
 *
 * <p>{@code remap = false}：目标与参数都是模组自己的类，不参与 Minecraft 的名称映射。
 */
@Mixin(value = WidgetContainer.class, remap = false)
public interface LmpWidgetContainerInvoker
{
    @Invoker("addButton")
    ButtonBase lmp$addButton(ButtonBase button, IButtonActionListener listener);
}

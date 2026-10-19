package com.chenjicheng.lmp.mixin;

import fi.dy.masa.litematica.gui.widgets.WidgetListSchematicPlacements;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;

import com.chenjicheng.lmp.client.gui.LmpPlacementEntry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在放置列表的每一行上挂一个「材料」按钮，点开该共享原理图的材料区设置界面。
 *
 * <p>这是玩家建立材料区的**主要入口**：玩家在列表里看到某个共享原理图时，最自然的动作就是
 * 直接点它，而不是先记住名字再去敲命令。
 *
 * <p><b>为什么是 mixin</b>：Litematica 没有提供「给放置行加按钮」的公开扩展点，Syncmatica 自己
 * 也是靠 mixin 做同样的事（它加的是「分享」按钮）。这里沿用同一套约定：读取目标类的
 * {@code buttonsStartX}，把按钮放在它左边，然后把这个游标左移，让后续再挂按钮的模组接着往左排。
 *
 * <p><b>降级</b>：注入点用 {@code require = 0}，配置文件 {@code required = false}。上游改了构造函数
 * 签名时，结果是「按钮不出现」而不是「客户端打不开放置列表」——按钮是可选功能，界面打不开不是。
 * 依赖缺失时目标类根本不会被加载，mixin 自然也不会应用。
 */
@Mixin(value = WidgetSchematicPlacement.class, remap = false)
public abstract class MixinWidgetSchematicPlacement
{
    /** 上游为「下一个按钮该放哪」维护的游标（从右往左）。 */
    @Shadow(remap = false)
    public int buttonsStartX;

    @Inject(method = "<init>(IIIIZLfi/dy/masa/litematica/schematic/placement/SchematicPlacement;"
            + "ILfi/dy/masa/litematica/gui/widgets/WidgetListSchematicPlacements;)V",
            at = @At("TAIL"), require = 0, remap = false)
    private void lmp$addMaterialButton(int x, int y, int width, int height, boolean isOdd,
                                       SchematicPlacement placement, int index,
                                       WidgetListSchematicPlacements list, CallbackInfo ci)
    {
        ButtonGeneric button = LmpPlacementEntry.attach((WidgetContainer) (Object) this, this.buttonsStartX, y,
                placement);

        if (button != null)
        {
            // 必须把游标左移：否则下一个挂按钮的模组（例如 Syncmatica）会与我们重叠在同一格
            this.buttonsStartX = button.getX() - 1;
        }
    }
}

package org.polyfrost.evergreenhud.mixins.client;

// TEMPORARY: paints a solid backdrop under the HUD layer for the docs screenshots
//? if = 1.8.9 {
/*import net.minecraft.client.gui.GameGui;
import net.minecraft.client.gui.GuiElement;
import org.polyfrost.evergreenhud.client.DevHudScreenshots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameGui.class)
public class Mixin_DevDocBackdrop {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;setupGuiState()V", shift = At.Shift.AFTER))
    private void evergreenhud$docBackdrop(float partialTick, CallbackInfo ci) {
        Integer color = DevHudScreenshots.INSTANCE.getBackdrop();
        if (color != null) GuiElement.fill(0, 0, 10000, 10000, color);
    }
}
*///?} else {
import net.minecraft.SharedConstants;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SharedConstants.class)
public class Mixin_DevDocBackdrop {}
//?}

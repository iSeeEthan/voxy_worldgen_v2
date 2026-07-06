package com.ethan.voxyworldgenv2.mixin;

import com.ethan.voxyworldgenv2.client.DebugRenderer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

@Mixin(DebugScreenEntries.class)
public class DebugScreenEntriesMixin {

    @Inject(method = "allEntries", at = @At("RETURN"), cancellable = true)
    private static void addVoxyEntry(CallbackInfoReturnable<Map<Identifier, DebugScreenEntry>> cir) {
        Map<Identifier, DebugScreenEntry> mutable = new LinkedHashMap<>(cir.getReturnValue());
        mutable.put(DebugRenderer.ID, DebugRenderer.INSTANCE);
        cir.setReturnValue(mutable);
    }

    // getEntry reads from a static map that doesn't include injected entries
    @Inject(method = "getEntry", at = @At("RETURN"), cancellable = true)
    private static void getVoxyEntry(Identifier id, CallbackInfoReturnable<DebugScreenEntry> cir) {
        if (DebugRenderer.ID.equals(id) && cir.getReturnValue() == null) {
            cir.setReturnValue(DebugRenderer.INSTANCE);
        }
    }
}

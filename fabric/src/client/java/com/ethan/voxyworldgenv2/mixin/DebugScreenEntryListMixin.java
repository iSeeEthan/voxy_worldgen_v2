package com.ethan.voxyworldgenv2.mixin;

import com.ethan.voxyworldgenv2.client.DebugRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(DebugScreenEntryList.class)
public class DebugScreenEntryListMixin {

    @Shadow private boolean isOverlayVisible;
    @Shadow private List<Identifier> currentlyEnabled;
    @Shadow private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    // our entry isn't in allStatuses so rebuildCurrentList never visits it, so we add it manually
    @Inject(method = "rebuildCurrentList", at = @At("RETURN"))
    private void injectVoxyEntry(CallbackInfo ci) {
        DebugScreenEntryStatus status = allStatuses.getOrDefault(DebugRenderer.ID, DebugScreenEntryStatus.IN_OVERLAY);
        if (status == DebugScreenEntryStatus.NEVER) return;
        boolean reducedInfo = Minecraft.getInstance().showOnlyReducedInfo();
        if (!DebugRenderer.INSTANCE.isAllowed(reducedInfo)) return;
        if (status == DebugScreenEntryStatus.ALWAYS_ON || (status == DebugScreenEntryStatus.IN_OVERLAY && isOverlayVisible)) {
            if (!currentlyEnabled.contains(DebugRenderer.ID)) {
                currentlyEnabled.add(DebugRenderer.ID);
            }
        }
    }

    @Inject(method = "getStatus", at = @At("RETURN"), cancellable = true)
    private void defaultVoxyStatus(Identifier id, CallbackInfoReturnable<DebugScreenEntryStatus> cir) {
        if (DebugRenderer.ID.equals(id) && cir.getReturnValue() == DebugScreenEntryStatus.NEVER) {
            cir.setReturnValue(DebugScreenEntryStatus.IN_OVERLAY);
        }
    }
}

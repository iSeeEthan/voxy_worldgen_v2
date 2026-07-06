package com.ethan.voxyworldgenv2.mixin;

import com.ethan.voxyworldgenv2.core.MinecraftServerExtension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements MinecraftServerExtension {
    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @Override
    public void voxyworldgen$runHousekeeping(BooleanSupplier haveTime) {
        // removed to prevent conflicts with async/c2me mods for now
    }

    @Override
    public void voxyworldgen$markHousekeeping() {
        // no longer used
    }
}

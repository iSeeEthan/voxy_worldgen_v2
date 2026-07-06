package com.ethan.voxyworldgenv2.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccess {
    @Accessor("emptyTicks")
    void setEmptyTicks(int ticks);
}

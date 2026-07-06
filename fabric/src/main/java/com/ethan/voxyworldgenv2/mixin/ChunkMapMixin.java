package com.ethan.voxyworldgenv2.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public interface ChunkMapMixin {
    @Invoker("tick")
    void invokeTick(BooleanSupplier booleanSupplier);

    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> invokeReadChunk(ChunkPos pos);
}

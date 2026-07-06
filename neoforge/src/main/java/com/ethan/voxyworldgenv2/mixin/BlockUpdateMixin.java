package com.ethan.voxyworldgenv2.mixin;

import com.ethan.voxyworldgenv2.core.ChunkUpdateTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class BlockUpdateMixin {

    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void onBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;

        // only the section the block sits in is dirty, so the next sync pass re-sends that section
        LevelChunk chunk = self.getChunkAt(pos);
        if (chunk != null) {
            ChunkUpdateTracker.getInstance().markDirty(chunk, pos.getY());
        }
    }
}

package com.ethan.voxyworldgenv2.core;

import java.util.function.BooleanSupplier;

public interface MinecraftServerExtension {
    void voxyworldgen$runHousekeeping(BooleanSupplier haveTime);
    void voxyworldgen$markHousekeeping();
}

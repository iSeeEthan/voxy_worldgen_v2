package com.ethan.voxyworldgenv2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// shared mod constants, the real entrypoints live in each loader
public final class VoxyWorldGenV2 {
    private VoxyWorldGenV2() {}

    public static final String MOD_ID = "voxyworldgenv2";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // bump on any wire format change, a mismatched peer is treated as unmodded
    public static final int PROTOCOL_VERSION = 1;
}

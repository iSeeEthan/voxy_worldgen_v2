package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.Config;

public final class ServerConfigState {
    private static volatile Config.ServerConfig current = null;
    private static volatile boolean canEdit = false;
    private static volatile boolean haveServerConfig = false;

    private ServerConfigState() {}

    public static void set(Config.ServerConfig config, boolean editable) {
        current = config;
        canEdit = editable;
        haveServerConfig = true;
    }

    public static void clear() {
        current = null;
        canEdit = false;
        haveServerConfig = false;
    }

    // true when we're connected to a modded server that sent its config
    public static boolean hasServerConfig() {
        return haveServerConfig && current != null;
    }

    public static boolean canEdit() {
        return canEdit;
    }

    // the server's values, or the local config snapshot if we haven't received any
    public static Config.ServerConfig get() {
        Config.ServerConfig c = current;
        return c != null ? c : Config.ServerConfig.snapshot();
    }
}

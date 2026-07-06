package com.ethan.voxyworldgenv2.platform;

import java.nio.file.Path;

// loader-specific bits the shared code needs
public interface IPlatformHelper {

    // folder voxyworldgenv2.json lives in
    Path getConfigDir();
}

package io.disconf.client.core;

import java.io.File;

/**
 * 配置文件变更回调接口，用户可实现这个接口来处理配置文件的变更。
 *
 * @author houkangxi
 */
public interface ConfigFileUpdateCallback {

    /**
     * 配置文件变更回调
     *
     * @param configFile - 配置文件，目錄為disconf下載目錄。
     */
    void onUpdate(File configFile);
}

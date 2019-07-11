package io.disconf.client.core;

import com.alibaba.fastjson.JSON;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Stat;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StreamUtils;

import java.io.*;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Zookeeper 节点监听，主要负责节点数据变更的处理。
 *
 * @author houkangxi
 */
public class ZookeeperWatcher {
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperWatcher.class);
    protected Properties oldProperties;
    protected final ResilientActiveKeyValueStore store;
    protected final Map<String, Resource> nodesResource;
    protected final BeanPropertyChangeHandler beanPropertyChangeHandler;
    protected final String fileDownloadDir;
    protected final String classpathDir;
    protected Collection<ConfigFileUpdateCallback> configFileUpdateCallbacks;

    public void setConfigFileUpdateCallbacks(Collection<ConfigFileUpdateCallback> configFileUpdateCallbacks) {
        logger.info("setConfigFileUpdateCallbacks: {}", configFileUpdateCallbacks);
        this.configFileUpdateCallbacks = configFileUpdateCallbacks;
    }

    public ZookeeperWatcher(String zkHosts, Map<String, Resource> nodesResource, BeanPropertyChangeHandler beanPropertyChangeHandler,
                            String fileDownloadDir) {
        URL rootClasspath = getClass().getClassLoader().getResource("");
        classpathDir = rootClasspath.getPath();
        this.nodesResource = nodesResource;
        this.fileDownloadDir = fileDownloadDir;
        this.beanPropertyChangeHandler = beanPropertyChangeHandler;
        store = new ResilientActiveKeyValueStore() {
            @Override
            public void process(WatchedEvent event) {
                logger.info("EVENT: {}", event);
                super.process(event);
                if (event.getType() == Event.EventType.NodeDataChanged) {
                    doUpdateSingle(event.getPath());// 节点数据变更处理
                    watch();
                }
                if (event.getState() == Event.KeeperState.Expired) {
                    store.reconnect();
                    watch();
                }
            }
        };
        try {
            store.connect(zkHosts);
            watch();
        } catch (Exception e) {
            logger.error("Fail to Connect zk: " + zkHosts, e);
        }
        //
        nodesResource.forEach((path, resource) -> {
            watchByThisIp(path, resource);
            // copy  Resources to classpath
            try {
                File configFile = copyToLocal(path, resource.getInputStream(), classpathDir);
                notifyCallback(configFile);
            } catch (Exception e) {
                logger.warn("复制到本地失败:" + path, e);
            }
        });
    }

    void watch() {
        for (String item : nodesResource.keySet()) {
            try {
                store.getZk().getData(item, store, new Stat());
                logger.info("watchNode: " + item);
            } catch (Exception e) {
                logger.error("Fail to Connect zk: " + item, e);
            }
        }
    }

    // 让web面板上可以看到本机的在线状态
    private void watchByThisIp(String path, InputStreamSource resource) {
        Map kvs;
        if (isProperties(path)) {
            Properties properties = new Properties();
            try {
                properties.load(resource.getInputStream());
            } catch (Exception e) {
            }
            kvs = new HashMap(properties);
        } else {
            kvs = Collections.EMPTY_MAP;
        }
        makeNodeTempPath(path, JSON.toJSONString(kvs));
    }

    public static final boolean isProperties(String node) {
        return node.endsWith(".properties");
    }

    // 本机的指纹 -- 唯一标识一个客户端进程
    private static final String FINER_PRINT;

    static {
        String fingerPrint = "";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            fingerPrint = addr.getHostAddress();
        } catch (UnknownHostException e) {
        }
        FINER_PRINT = fingerPrint + UUID.randomUUID();
    }

    protected void makeNodeTempPath(String path, String data) {
        String mainTypeFullStr = path + '/' + FINER_PRINT;
        try {
            store.createEphemeralNode(mainTypeFullStr, data, CreateMode.EPHEMERAL);
        } catch (Exception e) {
            logger.error("cannot create: " + mainTypeFullStr, e);
        }
    }

    // 获取集群IP列表
    public String[] getClusterHosts() {
        String firstNode = nodesResource.keySet().iterator().next();
        try {
            List<String> list = store.getZk().getChildren(firstNode, false);
            return list.stream().map(node -> node.substring(0, node.indexOf('_'))).toArray(String[]::new);
        } catch (Exception e) {
            logger.error("获取集群host失败", e);
        }
        return null;
    }

    // 主动刷新接口
    public void refreshConfigs() {
        logger.info("refreshConfigs...");
        doUpdate(nodesResource.keySet());
    }

    // 更新多个节点
    private void doUpdate(Collection<String> nodes) {
        logger.info("doUpdate config items: {}", nodes);
        Properties newConfig = new Properties();
        for (String node : nodes) {
            processNodeDataChange(newConfig, node);
        }
        updateSpringBeans(newConfig);
    }

    // 更新单一节点
    private void doUpdateSingle(String node) {
        logger.info("doUpdate config item: {}", node);
        Properties newConfig = new Properties();
        processNodeDataChange(newConfig, node);
        updateSpringBeans(newConfig);
    }

    // 更新Spring bean相关配置，@Value 注解的字段或方法
    private void updateSpringBeans(Properties newConfig) {
        beanPropertyChangeHandler.changeBeanProperties(oldProperties, newConfig);
        setOldProperties(newConfig);
    }

    private void processNodeDataChange(Properties newConfig, String node) {
        InputStream stream = null;
        try {
            stream = nodesResource.get(node).getInputStream();
            if (stream == null) {
                logger.error("DownloadFail: " + node);
            }
        } catch (IOException e) {
            logger.error("DownloadError: " + node, e);
        }
        if (stream != null) {
            try {
                byte[] data;
                try {
                    data = StreamUtils.copyToByteArray(stream);
                } finally {
                    stream.close();
                }
                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
                stream = new ByteArrayInputStream(data);
                File downloadDirFile = copyToLocal(node, byteArrayInputStream, fileDownloadDir);
                // copy to classpath:
                FileCopyUtils.copy(downloadDirFile, new File(classpathDir, downloadDirFile.getName()));
                notifyCallback(downloadDirFile);
                // 更新后修改临时节点数据
                watchByThisIp(node, new ByteArrayInputResource(data));
            } catch (Exception e) {
                logger.error("callbackError? ", e);
            }

            if (isProperties(node)) {
                try (Reader configuration = new InputStreamReader(stream, CharsetUtil.UTF_8)) {
                    newConfig.load(configuration);
                } catch (Exception e) {
                    logger.warn("ConfLoadError: " + node, e);
                }
            }
        }
    }

    private File copyToLocal(String node, InputStream stream, String configFileDir) {
        String item = node.substring(node.lastIndexOf('/') + 1);
        File configFile = new File(configFileDir, item);
        try (FileOutputStream fileOutputStream = new FileOutputStream(configFile)) {
            // copy 文件到 disconf 下载目录
            StreamUtils.copy(stream, fileOutputStream);
        } catch (Exception e) {
            logger.error("fail to Copy config: " + item, e);
        }
        return configFile;
    }

    private void notifyCallback(File configFile) {
        // 通知用户接口
        if (!CollectionUtils.isEmpty(configFileUpdateCallbacks)) {
            for (ConfigFileUpdateCallback callback : configFileUpdateCallbacks) {
                callback.onUpdate(configFile);
            }
        }
    }

    public void setOldProperties(Properties oldProperties) {
        this.oldProperties = oldProperties;
    }

    private static class ByteArrayInputResource implements InputStreamSource {
        final byte[] data;

        ByteArrayInputResource(byte[] data) {
            this.data = data;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }
    }
}

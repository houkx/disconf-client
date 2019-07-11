package io.disconf.client.core;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * ZK读写
 *
 * @author liaoqiqi
 * @version 2014-7-7
 */
public class ResilientActiveKeyValueStore extends ConnectionWatcher {

    protected static final Logger LOGGER = LoggerFactory.getLogger(ResilientActiveKeyValueStore.class);

    private static final Charset CHARSET = Charset.forName("UTF-8");

    // 最大重试次数
    public static final int MAX_RETRIES = 3;

    // 每次重试超时时间
    public static final int RETRY_PERIOD_SECONDS = 2;

    /**
     * @param path
     * @param value
     * @return void
     * @throws InterruptedException
     * @throws KeeperException
     * @Description: 创建一个临时结点，如果原本存在，则不新建, 如果存在，则更新值
     * @author liaoqiqi
     * @date 2013-6-14
     */
    public void createEphemeralNode(String path, String value, CreateMode createMode)
            throws InterruptedException, KeeperException {

        int retries = 0;
        KeeperException exception = null;
        while (retries++ < MAX_RETRIES) {
            try {
                Stat stat = zk.exists(path, false);
                if (stat == null) {
                    zk.create(path, value.getBytes(CHARSET), Ids.OPEN_ACL_UNSAFE, createMode);
                } else if (value != null) {
                    zk.setData(path, value.getBytes(CHARSET), stat.getVersion());
                }
                LOGGER.info("创建临时节点成功: path = " + path + " , stat = " + stat);
                return;
            } catch (KeeperException e) {
                exception = e;
                LOGGER.warn("createEphemeralNode 连接失败,将重试: " + retries, e);
                reconnect();// 重新连接
                // sleep then retry
                int sec = RETRY_PERIOD_SECONDS * retries;
                LOGGER.warn("sleep " + sec);
                TimeUnit.SECONDS.sleep(sec);
            }
        }
        throw exception;
    }

}
package io.disconf.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.disconf.client.core.BeanPropertyChangeHandler;
import io.disconf.client.core.ConfigFileUpdateCallback;
import io.disconf.client.core.PropertiesConfig;
import io.disconf.client.core.ZookeeperWatcher;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Disconf 配置入口类, 配置只需要关注构造方法。
 *
 * @author houkangxi
 */
public class DisConfPropertyConfigurer extends PropertyPlaceholderConfigurer implements
        BeanFactoryPostProcessor, BeanPostProcessor, PriorityOrdered, BeanNameAware, BeanFactoryAware, AutowireCandidateResolver//
        , ApplicationListener<ContextRefreshedEvent> {
    private static final Logger logger = LoggerFactory.getLogger(DisConfPropertyConfigurer.class);
    private final BeanPropertyChangeHandler beanPropertyChangeHandler = new BeanPropertyChangeHandler();
    private ZookeeperWatcher zookeeperWatcher;
    //  支持本地配置文件, 用来做差异化控制, 如果有相同的Key,则本地配置会覆盖中心化配置
    private final File localConf = new File("conf/app.properties");

    public ZookeeperWatcher getZookeeperWatcher() {
        return zookeeperWatcher;
    }

    /**
     * 主动刷新配置接口 -- 即使不使用disconf 也可以刷新配置, 所有 @Value 关联的bean的属性或setter方法都会调用
     *
     * @param oldProperties
     * @param newProperties
     */
    public void changeBeanProperties(Properties oldProperties, Properties newProperties) {
        if (oldProperties == null) {
            oldProperties = new Properties();
            oldProperties.put("old", "");
        }
        beanPropertyChangeHandler.changeBeanProperties(oldProperties, newProperties);
    }

    /**
     * 构造方法
     *
     * @param appName - 应用名
     * @param items   - 配置项列表
     */
    public DisConfPropertyConfigurer(String appName, String... items) {
        this(appName, new DefaultResourceLoader(), items);
    }

    /**
     * 构造方法
     *
     * @param appName        - 应用名
     * @param items          - 配置项列表
     * @param resourceLoader - 资源加载器
     */
    public DisConfPropertyConfigurer(String appName, ResourceLoader resourceLoader, String... items) {
        Properties disConf = readDisConfProperties(getDisConfigFilePath(appName), resourceLoader);
        List<String> itemsList = new ArrayList<>(Arrays.asList(items));
        if (!localConf.exists()) {
            localConf.getParentFile().mkdir();
            try {
                localConf.createNewFile();
            } catch (IOException e) {
            }
        }
        if (disConf == null) {
            logger.warn("Disconf 配置没找到, 使用本地模式.");
            itemsList.add(localConf.toURI().toString());// add Local
            setLocations(itemsList.stream().filter(item -> ZookeeperWatcher.isProperties(item))
                    .map(item -> resourceLoader.getResource(item)).toArray(Resource[]::new));
            return;
        }
        StringBuilder urlBuilder = new StringBuilder(256);
        String confServerHost = disConf.getProperty("conf_server_host");
        if (confServerHost.startsWith("http")) {
            urlBuilder.append(confServerHost);
        } else {
            urlBuilder.append("http://").append(confServerHost);
        }
        StringBuilder zkHost = new StringBuilder(urlBuilder.length() + 20);
        zkHost.append(urlBuilder).append("/api/zoo/hosts");
        String zookeeperHosts;
        try {
            URL url = new URL(zkHost.toString());
            InputStream stream = url.openStream();
            String json = StreamUtils.copyToString(stream, CharsetUtil.UTF_8);
            stream.close();
            JSONObject object = JSON.parseObject(json);
            zookeeperHosts = object.getString("value");
            System.setProperty("disconf.zookeper.hosts", zookeeperHosts);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String version = disConf.getProperty("version"), env = disConf.getProperty("env");

        urlBuilder.append("/api/config/file?type=0&app=").append(appName)//
                .append("&version=").append(version)//
                .append("&env=").append(env).append("&key=");
        final String configItemUrlPrefix = urlBuilder.toString();
        String configItemNodePrefix = String.format("/disconf/%s_%s_%s/file/", appName, version, env);
        //
        Map<String, Resource> nodesResource = itemsList.stream().collect(Collectors.toMap(item -> configItemNodePrefix + item,
                item -> resourceLoader.getResource(configItemUrlPrefix + item)
        ));
        String fileDownloadDir = disConf.getProperty("user_define_download_dir");

        zookeeperWatcher = new ZookeeperWatcher(zookeeperHosts, new HashMap<>(nodesResource), beanPropertyChangeHandler, fileDownloadDir);

        nodesResource.put(localConf.getName(), resourceLoader.getResource(localConf.toURI().toString()));
        setLocations(nodesResource.entrySet().stream().filter(entry -> ZookeeperWatcher.isProperties(entry.getKey()))
                .map(entry -> entry.getValue()).toArray(Resource[]::new));
        logger.info("fileDownloadDir = {}, cur = {}", fileDownloadDir, System.getProperty("user.dir"));
    }

    /**
     * 保存到本地配置文件
     *
     * @param config
     * @param comment
     * @throws IOException
     */
    public void saveToLocal(Properties config, String comment) throws IOException {
        if (localConf.exists()) {
            PropertiesConfig conf = new PropertiesConfig(localConf);
            conf.saveToFile(config, comment);
        }
    }

    @Override
    protected Properties mergeProperties() throws IOException {
        Properties properties = super.mergeProperties();
        if (zookeeperWatcher != null) {
            zookeeperWatcher.setOldProperties(properties);
        }
        return properties;
    }

    protected Properties readDisConfProperties(String path, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(path);
        try (InputStream stream = resource.getInputStream()) {
            if (stream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        } catch (Exception e) {
            return null;
        }
    }

    protected String getDisConfigFilePath(String appName) {
        String tomcatHome = System.getenv("CATALINA_HOME");
        if (tomcatHome == null) {
            tomcatHome = System.getProperty("catalina.home");
        }
        logger.info("tomcatHome = {}", tomcatHome);
        if (tomcatHome != null) {
            return new File(tomcatHome, "appconfig" + File.separator + appName + File.separator + "disconf.properties").toURI().toString();
        }
        return "classpath:disconf.properties";
    }

    @Override
    public int getOrder() {
        return beanPropertyChangeHandler.getOrder();
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        super.postProcessBeanFactory(beanFactory);
        beanPropertyChangeHandler.postProcessBeanFactory(beanFactory);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return beanPropertyChangeHandler.postProcessBeforeInitialization(bean, beanName);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return beanPropertyChangeHandler.postProcessAfterInitialization(bean, beanName);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        super.setBeanFactory(beanFactory);
        beanPropertyChangeHandler.setBeanFactory(beanFactory);
    }

    @Override
    public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
        return beanPropertyChangeHandler.isAutowireCandidate(bdHolder, descriptor);
    }

    public boolean isRequired(DependencyDescriptor descriptor) {
        return beanPropertyChangeHandler.isRequired(descriptor);
    }

    @Override
    public Object getSuggestedValue(DependencyDescriptor descriptor) {
        return beanPropertyChangeHandler.getSuggestedValue(descriptor);
    }

    public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, String beanName) {
        return beanPropertyChangeHandler.getLazyResolutionProxyIfNecessary(descriptor, beanName);
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (zookeeperWatcher != null) {
            try {
                Map<String, ConfigFileUpdateCallback> maps = event.getApplicationContext().getBeansOfType(ConfigFileUpdateCallback.class);
                if (maps != null && maps.size() > 0) {
                    zookeeperWatcher.setConfigFileUpdateCallbacks(maps.values());
                }
            } catch (Exception e) {
            }
        }
    }
}

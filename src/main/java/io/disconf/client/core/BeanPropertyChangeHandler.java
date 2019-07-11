package io.disconf.client.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.*;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PropertiesLoaderSupport;
import org.springframework.util.*;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * process BeanPropertyChange
 *
 * @author houkangxi 2018/9/27 11:46
 */
public class BeanPropertyChangeHandler extends ContextAnnotationAutowireCandidateResolver
        implements BeanFactoryPostProcessor
        , PriorityOrdered, BeanPostProcessor, EnvironmentAware {
    private static final Logger logger = LoggerFactory.getLogger(BeanPropertyChangeHandler.class);
    private static final String PLACEHOLDER_PREFIX = PlaceholderConfigurerSupport.DEFAULT_PLACEHOLDER_PREFIX;
    private static final PropertyPlaceholderHelper propertyPlaceholderHelper = new PropertyPlaceholderHelper(PLACEHOLDER_PREFIX, PlaceholderConfigurerSupport.DEFAULT_PLACEHOLDER_SUFFIX, PlaceholderConfigurerSupport.DEFAULT_VALUE_SEPARATOR
            , true);
    private HashMap<String, Set<DependencyDescriptor>> keyToDescriptor = new HashMap(128);
    private DefaultListableBeanFactory beanFactory;
    private AutowireCandidateResolver origAutowireCandidateResolver;
    // xmlBeanProperties: <beanName,<propertyName, placeHolderExpression>>
    private HashMap<String, Map<String, String>> xmlBeanProperties = new HashMap(16);
    private Set<String> patternLikeKeys;

    public Object getSuggestedValue(final DependencyDescriptor descriptor) {
        Object value = super.getSuggestedValue(descriptor);
        if (value instanceof String) {
            saveDependency((String) value, descriptor);
        }
        return value;
    }

    private void saveDependency(String placeHolderValue, final DependencyDescriptor descriptor) {
        propertyPlaceholderHelper.replacePlaceholders(placeHolderValue, placeholderName -> {
            int valueFlagStart = placeholderName.indexOf(':');
            String name = valueFlagStart < 0 ? placeholderName : placeholderName.substring(0, valueFlagStart);
            putDependencyDescriptor(keyToDescriptor, name, descriptor);
            return "";
        });
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        Map<String, String> vs = xmlBeanProperties.remove(beanName);//invoke only once each bean
        if (vs != null && vs.size() > 0) {
            Class beanClass = bean.getClass();
            for (Map.Entry<String, String> propertyValue : vs.entrySet()) {
                String property = propertyValue.getKey();
                String placeHolderValue = propertyValue.getValue();
                DependencyDescriptor descriptor = parseDependencyDescriptor(beanClass, property);
                if (descriptor != null) {
                    saveDependency(placeHolderValue, descriptor);
                    logger.debug("saveDependency: property={},value={}", property, placeHolderValue);
                }
            }
        }
        return bean;
    }


    private DependencyDescriptor parseDependencyDescriptor
            (Class beanClass, String propertyName) {
        PropertyDescriptor propertyDescriptor = BeanUtils.getPropertyDescriptor(beanClass, propertyName);
        if (propertyDescriptor != null) {
            MethodParameter methodParameter = new MethodParameter(propertyDescriptor.getWriteMethod(), 0);
            return new DependencyDescriptor(methodParameter, true);
        }
        return null;
    }

    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {
        DefaultListableBeanFactory bf = (DefaultListableBeanFactory) beanFactory;
        this.beanFactory = bf;
        this.origAutowireCandidateResolver = bf.getAutowireCandidateResolver();
        bf.setAutowireCandidateResolver(this);
        bf.setTypeConverter(new JsonTypeConverter());

        registerXmlBeanProperties();

        try {
            Field fieldResolver = ReflectionUtils.findField(bf.getClass(), "embeddedValueResolvers");
            fieldResolver.setAccessible(true);
            Object resolverList = fieldResolver.get(bf);
            List list = (List) resolverList;
            final RegexStringValueResolver valueResolver = new RegexStringValueResolver();
            if (list == null || list.isEmpty()) {
                logger.warn("embeddedValueResolvers list is EMPTY.");
                valueResolver.origValueResolver = new StringValueResolver() {
                    @Override
                    public String resolveStringValue(String strVal) {
                        return propertyPlaceholderHelper.replacePlaceholders(strVal, valueResolver.allProperties());
                    }
                };
            } else {
                valueResolver.origValueResolver = (StringValueResolver) list.get(0);
            }
            if (list != null) {
                list.add(0, valueResolver);
            } else {
                bf.addEmbeddedValueResolver(valueResolver);
            }
        } catch (Exception e) {
            logger.warn("Fail to add Custom embeddedValueResolvers", e);
        }
    }

    private void registerXmlBeanProperties() {
        String[] beanNames = beanFactory.getBeanDefinitionNames();
        if (beanNames == null || beanNames.length == 0) {
            return;
        }
        for (String beanName : beanNames) {
            logger.debug("try parse beanProperty for {}", beanName);
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            PropertyValue[] vs = definition.getPropertyValues().getPropertyValues();
            HashMap<String, String> values = new HashMap(vs.length);
            for (PropertyValue pv : vs) {
                Object v = pv.getValue();
                if (v instanceof TypedStringValue) {
                    TypedStringValue typedStringValue = (TypedStringValue) v;
                    String strVal = typedStringValue.getValue();
                    if (strVal.startsWith(PLACEHOLDER_PREFIX)) {
                        values.put(pv.getName(), strVal);
                    }
                } else if (v instanceof String) {
                    String strVal = (String) v;
                    if (strVal.startsWith(PLACEHOLDER_PREFIX)) {
                        values.put(pv.getName(), strVal);
                    }
                }
            }
            if (values.size() > 0) {
                logger.debug("  saveBeanProperty for {}=> {}", beanName, values);
                xmlBeanProperties.put(beanName, values);
            }
        }
    }

    private Properties loadAllProperties() {
        Map<String, Properties> systemPropertiesMap = beanFactory.getBeansOfType(Properties.class);
        Properties sysProperties = systemPropertiesMap.values().iterator().next();
        Properties allProperties;
        try {
            allProperties = new Properties();
            Class<PropertiesLoaderSupport> supportClass = PropertiesLoaderSupport.class;
            Map<String, PropertiesLoaderSupport> propertiesMap = beanFactory.getBeansOfType(supportClass);
            for (PropertiesLoaderSupport support : propertiesMap.values()) {
                Method methodMerge = supportClass.getDeclaredMethod("mergeProperties");
                methodMerge.setAccessible(true);
                Properties properties = (Properties) methodMerge.invoke(support);
                allProperties.putAll(properties);
            }
            // systemProperties 优先，所以在最后put.
            allProperties.putAll(sysProperties);
        } catch (Exception e) {
            allProperties = sysProperties;
            logger.warn("no sourcesProperties.", e);
        }
        return allProperties;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }


    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    private class RegexStringValueResolver implements StringValueResolver {
        StringValueResolver origValueResolver;
        Properties allProperties;

        synchronized Properties allProperties() {
            if (allProperties == null) {
                allProperties = loadAllProperties();
            }
            return allProperties;
        }

        @Override
        public String resolveStringValue(String strVal) {
            if (strVal.startsWith(PLACEHOLDER_PREFIX) && isSimpleLikePattern(strVal)) {
                if (patternLikeKeys == null) {
                    patternLikeKeys = new HashSet<String>();
                }
                String regexKey = strVal.substring(PLACEHOLDER_PREFIX.length(), strVal.length() - 1);
                patternLikeKeys.add(regexKey);
                return getMapJson(allProperties(), regexKey, origValueResolver);
            }
            return origValueResolver.resolveStringValue(strVal);
        }

    }

    private static boolean isSimpleLikePattern(String strVal) {
        return strVal.indexOf('*') >= 0;
    }

    private static String getMapJson(Properties properties, String regex, StringValueResolver origValueResolver) {
        // build a json: {k1:v1, k2:v2, ...}, all keys match regex.
        StringBuilder jsonBuilder = new StringBuilder(512);
        // find Key match regex in properties:
        for (Map.Entry entry : properties.entrySet()) {
            String name = (String) entry.getKey();
            if (PatternMatchUtils.simpleMatch(regex, name)) {
                String v = (String) entry.getValue();
                v = origValueResolver.resolveStringValue(v);

                char c;
                if (jsonBuilder.length() > 0) {
                    jsonBuilder.append(",\"").append(name).append("\":");
                } else {
                    jsonBuilder.append("{\"").append(name).append("\":");
                }
                if (v.length() > 0 && ((c = v.charAt(0)) == '{' || c == '[')) {
                    jsonBuilder.append(v);
                } else {
                    jsonBuilder.append('"').append(v).append('"');
                }
            }
        }
        jsonBuilder.append('}');
        return jsonBuilder.toString();
    }


    private void putDependencyDescriptor(Map<String, Set<DependencyDescriptor>> keyToDescriptor, String key, DependencyDescriptor dependencyDescriptor) {
        Set<DependencyDescriptor> descriptors = keyToDescriptor.get(key);
        if (descriptors != null) {
            descriptors.add(dependencyDescriptor);
        } else {
            keyToDescriptor.put(key, descriptors = new HashSet<DependencyDescriptor>());
            descriptors.add(dependencyDescriptor);
        }
    }

    private void putDependencyDescriptor(Map<String, Set<DependencyDescriptor>> keyToDescriptor, String key, Set<DependencyDescriptor> dependencyDescriptor) {
        Set<DependencyDescriptor> descriptors = keyToDescriptor.get(key);
        if (descriptors != null) {
            descriptors.addAll(dependencyDescriptor);
        } else {
            keyToDescriptor.put(key, descriptors = new HashSet<DependencyDescriptor>());
            descriptors.addAll(dependencyDescriptor);
        }
    }

    public void changeBeanProperties(Properties oldProperties, Properties newProperties) {
        if (CollectionUtils.isEmpty(oldProperties) || CollectionUtils.isEmpty(newProperties)) {
            return;
        }
        logger.info("刷新配置.");
        // reset to orig
        beanFactory.setAutowireCandidateResolver(origAutowireCandidateResolver);

        HashSet<String> changedKeys = new HashSet<String>(newProperties.size());
        for (Map.Entry<Object, Object> entry : newProperties.entrySet()) {
            String k = (String) entry.getKey();
            String v = (String) entry.getValue();
            String oldV = oldProperties.getProperty(k);
            if (v.equals(oldV)) {
                if (v.contains(PLACEHOLDER_PREFIX)) {
                    v = propertyPlaceholderHelper.replacePlaceholders(v, newProperties);
                    oldV = propertyPlaceholderHelper.replacePlaceholders(oldV, oldProperties);
                    if (!v.equals(oldV)) {
                        changedKeys.add(k);
                    }
                }
            } else {
                changedKeys.add(k);
            }
        }
        if (oldProperties.size() > newProperties.size()) {
            HashSet<String> set = new HashSet(oldProperties.keySet());
            set.removeAll(newProperties.keySet());
            changedKeys.addAll(set);
            logger.info("删除了这些配置：{}", set);
        }
        if (changedKeys.isEmpty()) {
            logger.info("配置没变更。。");
            return;
        }
        HashMap<String, Set<DependencyDescriptor>> dependencyDescriptors = new HashMap<String, Set<DependencyDescriptor>>(changedKeys.size());
        for (String key : changedKeys) {
            Set<DependencyDescriptor> descriptors = keyToDescriptor.get(key);
            if (descriptors != null) {
                putDependencyDescriptor(dependencyDescriptors, key, descriptors);
            }
            if (patternLikeKeys != null) {
                for (String regexKey : patternLikeKeys) {
                    if (PatternMatchUtils.simpleMatch(regexKey, key)) {
                        logger.info("new Added key:{}, look at it with regex", key);
                        Set<DependencyDescriptor> cs = keyToDescriptor.get(regexKey);
                        if (cs != null) {
                            putDependencyDescriptor(dependencyDescriptors, regexKey, cs);
                        }
                    }
                }
            }
        }

        logger.info("配置发生变更: changedKeys = {}, dependencyDescriptors = {}", changedKeys, dependencyDescriptors);

        injectBeanProperties(newProperties, dependencyDescriptors);
    }

    private void injectBeanProperties(Properties properties,//
                                      Map<String, Set<DependencyDescriptor>> dependencyDescriptors) {
        InheritedValueResolver valueResolver = new InheritedValueResolver(properties);

        TypeConverter converter = beanFactory.getTypeConverter();
        for (Map.Entry<String, Set<DependencyDescriptor>> entry :
                dependencyDescriptors.entrySet()) {
            String key = entry.getKey(), value;
            if (isSimpleLikePattern(key)) {
                value = getMapJson(properties, key, valueResolver);
            } else {
                value = properties.getProperty(key);
                if (value != null) {
                    value = valueResolver.resolveStringValue(value);
                } else {
                    logger.warn("property not found by Key: {}", key);
                }
            }
            if (value != null) {
                for (DependencyDescriptor descriptor : entry.getValue()) {
                    Class<?> type = descriptor.getDependencyType();
                    Field field = descriptor.getField();
                    if (field != null) {
                        try {
                            Object bean = beanFactory.getBean(field.getDeclaringClass());
                            Object property = converter.convertIfNecessary(value, type, field);
                            ReflectionUtils.makeAccessible(field);
                            field.set(bean, property);
                        } catch (Exception e) {
                            logger.warn("Fail to setFieldProperty. key={}", key, e);
                        }
                    } else {
                        MethodParameter methodParameter = descriptor.getMethodParameter();
                        if (methodParameter != null) {
                            try {
                                Method method = methodParameter.getMethod();
                                Object bean = beanFactory.getBean(method.getDeclaringClass());
                                Object property = converter.convertIfNecessary(value, type, methodParameter);
                                ReflectionUtils.makeAccessible(method);
                                method.invoke(bean, property);
                            } catch (Exception e) {
                                logger.warn("Fail to setMethodProperty. key={}", key, e);
                            }
                        }
                    }
                }
            }

        }
    }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    class InheritedValueResolver implements PropertyPlaceholderHelper.PlaceholderResolver, StringValueResolver {
        final Properties properties;

        InheritedValueResolver(Properties properties) {
            this.properties = properties;
        }

        @Override
        public String resolvePlaceholder(String k) {
            String v = properties.getProperty(k);
            return v != null ? v : environment.getProperty(k);
        }

        @Override
        public String resolveStringValue(String s) {
            return propertyPlaceholderHelper.replacePlaceholders(s, this);
        }
    }

}

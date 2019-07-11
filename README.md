# disconf-client
简洁而强大的spring配置工具
⋅⋅*  代码量大约只有原作者的1/10, 没有繁琐的二次扫描啥的.
⋅⋅*  解决了几个bug, 比如debug=true时断线就不重连,莫名其名的断线等.
⋅⋅*  支持json和 * 通配符配置
⋅⋅*  配置更简洁, 业务代码无侵入, 只需要关注原生spring @Value注解
⋅⋅* 配置变更后自动修改@Value注解的字段,和自动调用@Value注解的setter方法
⋅⋅*  即使不使用disconf也能使用框架的接口主动修改配置

``` properties
app.title=someGame
app.tags=["play","war"]
app.user.hobby.lilei=fishing
app.user.hobby.HanMeimei=reading
```
-----------------------------------------------------------------------
```xml
 <!-- disconf.xml -->
<?xml version="1.0" encoding="utf-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-2.5.xsd">

    <!-- config center config -->
    <bean id="disConfPropertyConfigurer" class="io.disconf.client.DisConfPropertyConfigurer">
        <constructor-arg index="0" value="myApp"/> <!--  appName -->
        <constructor-arg index="1">
            <!--  所有配置项 -->
            <array>
                <value>settings.properties</value>
                <value>log4j2.xml</value>
            </array>
        </constructor-arg>
    </bean>

</beans>
```
---------------------------------------------------------------------------------------
``` xml
 <!-- app-test.xml -->
<?xml version="1.0" encoding="utf-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xmlns:context="http://www.springframework.org/schema/context"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-2.5.xsd http://www.springframework.org/schema/context http://www.springframework.org/schema/context/spring-context.xsd">

    <context:component-scan base-package="test.spring.base"/>
</beans>
```
---------------------------------------------------------------------------------------
```java
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration({"classpath:disconf.xml", "classpath:app-test.xml"})
public class ConfigTest_ {

    @Autowired
    Configs configs;

    @Autowired
    DisConfPropertyConfigurer disConfPropertyConfigurer;

    @Test
    public void test_config() {
        System.out.println("configs = " + configs);

        Properties properties = new Properties();
        properties.put("app.title", "myApp");
        properties.put("app.title", "myApp");
        properties.put("app.user.hobby.NewResetUser", "WriteBug");
        // when use disconf, this method is auto invoke when Config Changed
        // 当使用disconf时,这个方法是自动调用的;  由于第一个参数传的是null, 所以这里是重置
        disConfPropertyConfigurer.changeBeanProperties(null, properties);

        System.out.println("** After Refresh:\nconfigs = " + configs);
    }

}
```



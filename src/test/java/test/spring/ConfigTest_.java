package test.spring;

import io.disconf.client.DisConfPropertyConfigurer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import test.spring.base.Configs;

import java.util.Properties;

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

package test.spring.base;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

@Component
public class Configs {
    @Value("${app.tags:[]}")
    public String[] tags;
    public String title;

    public Map<String,String> userHobby;

    @Value("${app.title}")
    public void setTitle(String title) {
        this.title = title;
    }

    @Value("${app.user.hobby.*}")
    public void setUserHobby(Map<String, String> userHobby) {
        this.userHobby = userHobby;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Configs{");
        sb.append("tags=").append(Arrays.toString(tags));
        sb.append(", title='").append(title).append('\'');
        sb.append(", userHobby=").append(userHobby);
        sb.append('}');
        return sb.toString();
    }
}

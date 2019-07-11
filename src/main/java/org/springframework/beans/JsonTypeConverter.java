package org.springframework.beans;

import com.alibaba.fastjson.JSON;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.util.Assert;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

/**
 * 支持 json 类型的转换器:
 * <pre>
 *   @Value( "${ tags: [] }")
 *   private Set<String> tags;
 *
 *   @Value( "${ tagsMap: {'u1': 1} }")
 *   public Map<String,Integer> map
 *
 * </pre>
 *
 * @author houkangxi 2018/9/28 9:25
 */
public class JsonTypeConverter extends SimpleTypeConverter {
    // 這三個方法適用於 spring4.x
    public <T> T convertIfNecessary(Object value, Class<T> requiredType) throws TypeMismatchException {
        return super.convertIfNecessary(convertJsonIfNecessary(value, requiredType), requiredType);
    }

    public <T> T convertIfNecessary(Object value, Class<T> requiredType, MethodParameter methodParam) throws TypeMismatchException {
        return super.convertIfNecessary(convertJsonIfNecessary(value, methodParam.getGenericParameterType()), requiredType, methodParam);
    }

    public <T> T convertIfNecessary(Object value, Class<T> requiredType, Field field) throws TypeMismatchException {
        return super.convertIfNecessary(convertJsonIfNecessary(value, field.getGenericType()), requiredType, field);
    }

    // 這個方法适用于 spring5.x
    public <T> T convertIfNecessary(Object value, Class<T> requiredType,
                                    TypeDescriptor typeDescriptor) throws TypeMismatchException {

        value = convertJsonIfNecessary(value, typeDescriptor.getResolvableType().getType());
        Assert.state(this.typeConverterDelegate != null, "No TypeConverterDelegate");
        try {
            return this.typeConverterDelegate.convertIfNecessary(null, null, value, requiredType, typeDescriptor);
        } catch (ConverterNotFoundException | IllegalStateException ex) {
            throw new ConversionNotSupportedException(value, requiredType, ex);
        } catch (ConversionException | IllegalArgumentException ex) {
            throw new TypeMismatchException(value, requiredType, ex);
        }
    }

    private Object convertJsonIfNecessary(Object value, Type requiredType) {
        if (value instanceof String) {
            String json = ((String) value).trim();
            if (json.length() > 1) {
                char c0 = json.charAt(0);
                if (c0 == '{' || c0 == '[') {
                    return JSON.parseObject(json, requiredType);
                }
            }
        }
        return value;
    }
}

package com.isles.common.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 序列化/反序列化辅助：用于 JSON 列（评论媒体等）与安全过滤器手写错误体。
 *
 * <p>Web 层的请求/响应仍由 Spring MVC 的 Jackson 转换器处理，这里只覆盖非 HTTP 边界场景。
 */
@Component
public class JsonSupport {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String toJson(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T fromJson(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}

package com.isles.common.web;

import com.isles.common.api.ApiError;
import com.isles.common.api.ErrorResponse;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 在安全过滤器链中（未进入 MVC 异常处理）直接写出统一错误信封。
 */
@Component
public class JsonErrorWriter {

    private final JsonSupport jsonSupport;

    public JsonErrorWriter(JsonSupport jsonSupport) {
        this.jsonSupport = jsonSupport;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode) throws IOException {
        String requestId = RequestIdFilter.resolve(request);
        ErrorResponse body = new ErrorResponse(
                new ApiError(errorCode.name(), errorCode.defaultMessage(), List.of(), requestId));
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonSupport.toJson(body));
    }
}

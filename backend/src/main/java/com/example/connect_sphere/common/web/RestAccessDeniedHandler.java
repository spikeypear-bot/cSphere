package com.example.connect_sphere.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * The 403 counterpart to {@link RestAuthenticationEntryPoint}: the caller
 * proved who they are, and that identity is not allowed to do this.
 *
 * Which of the two runs is not a choice made here —
 * {@code ExceptionTranslationFilter} decides by looking at the current
 * authentication. Anonymous means the caller never authenticated, so the answer
 * is 401 "who are you"; a real principal means 403 "not you". One
 * {@code hasRole(...)} rule therefore produces both codes correctly with no
 * extra configuration.
 *
 * This is AU06's "clear message on an unauthorised action" for rejections the
 * filter chain makes. Denials raised by {@code @PreAuthorize} inside a service
 * are handled by {@link ApiExceptionHandler} instead — see the note there.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        // Unlike the 401, naming the role here would be safe — the caller is
        // already authenticated. It is still left out: the frontend routes by
        // role and should never send the request in the first place, so a
        // generic line is enough and keeps the endpoint from advertising what
        // role would have worked.
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of("You do not have permission to perform this action."));
    }
}

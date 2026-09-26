package com.wuling.common.exception;

import com.wuling.common.api.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    @Test
    void missingResourceReturnsHttp404() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.POST, "api/v1/app/comments");

        ResponseEntity<Result<Void>> response = handler.handleNotFound(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(404, body.getCode());
        assertEquals("资源不存在", body.getMessage());
    }
}
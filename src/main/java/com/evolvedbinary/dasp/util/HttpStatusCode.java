package com.evolvedbinary.dasp.util;

public enum HttpStatusCode {

    OK(200),

    NOT_MODIFIED(304),

    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    NOT_FOUND(404),
    FORBIDDEN(403),

    INTERNAL_SERVER_ERROR(500);

    public final int code;

    HttpStatusCode(final int code) {
        this.code = code;
    }
}

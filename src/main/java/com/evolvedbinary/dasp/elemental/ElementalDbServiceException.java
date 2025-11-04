package com.evolvedbinary.dasp.elemental;

public class ElementalDbServiceException extends Exception {
    public ElementalDbServiceException(final Throwable cause) {
        super(cause);
    }

    public ElementalDbServiceException(final String message) {
        super(message);
    }
}
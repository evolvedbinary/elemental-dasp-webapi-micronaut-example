package com.evolvedbinary.dasp.util;

import java.net.URI;
import java.net.URISyntaxException;

public class Util {

    public static URI uri(final String str) throws IllegalArgumentException {
        try {
            return new URI(str);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

package com.evolvedbinary.dasp.domain;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
@JacksonXmlRootElement(localName="error", namespace="http://ns.declarative.amsterdam/dasp")
public record Error(
    String code,
    String message
) { }

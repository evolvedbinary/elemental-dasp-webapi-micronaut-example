package com.evolvedbinary.dasp.domain;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
@JacksonXmlRootElement(localName="document", namespace="http://ns.declarative.amsterdam/dasp")
public record Document(
    @JacksonXmlProperty(isAttribute=true)
    String id,

    @JacksonXmlProperty(isAttribute=true)
    Instant created,

    @JacksonXmlProperty(isAttribute=true)
    Instant lastModified,

    @JacksonXmlProperty(isAttribute=true)
    String owner,

    @JacksonXmlProperty(isAttribute=true)
    String group,

    @JacksonXmlProperty(isAttribute=true)
    String permissions) {
}

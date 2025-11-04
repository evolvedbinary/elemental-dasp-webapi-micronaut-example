package com.evolvedbinary.dasp.domain;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
@JacksonXmlRootElement(localName="documents", namespace="http://ns.declarative.amsterdam/dasp")
public record Documents(
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName="document", namespace="http://ns.declarative.amsterdam/dasp")
    List<Document> documents
) { }

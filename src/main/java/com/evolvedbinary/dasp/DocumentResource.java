package com.evolvedbinary.dasp;

import com.evolvedbinary.dasp.elemental.ElementalDbServiceException;
import com.evolvedbinary.dasp.elemental.ElementalDbService;
import com.evolvedbinary.dasp.domain.Document;
import com.evolvedbinary.dasp.domain.Documents;
import com.evolvedbinary.dasp.domain.Error;
import com.evolvedbinary.dasp.util.HttpStatusCode;
import com.evolvedbinary.j8fu.tuple.Tuple2;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.exist.dom.persistent.DefaultDocumentSet;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.MutableDocumentSet;
import org.exist.security.PermissionDeniedException;
import org.exist.util.LockException;
import org.exist.util.StringInputSource;
import org.exist.xquery.XPathException;
import org.exist.xupdate.XUpdateProcessor;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static com.evolvedbinary.dasp.util.Util.uri;
import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;

@Path("/document")
public class DocumentResource {

    private static final URI DOCUMENTS_COLLECTION_PATH = uri("/db/dasp-documents");

    @Inject
    ElementalDbService elemental;

    @PUT
    @Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    public Response createDocument(final String content, @Context final HttpHeaders headers) {
        try {
            final Document domainDocument = elemental.withConnection(elemental.systemUser(), connection ->
                elemental.transaction(connection, transaction ->
                    elemental.writeCollection(DOCUMENTS_COLLECTION_PATH, connection, transaction, collection -> {
                        final URI documentUri = elemental.storeDocument(headers.getMediaType().toString(), new StringInputSource(content), collection, connection, transaction);
                        return elemental.readDocument(Paths.get(documentUri).getFileName().toString(), collection, connection, transaction, dbDocument -> toDomainDocument(dbDocument));
                    })
                )
            );

            return Response.created(new URI("/document/" + domainDocument.id()))
                .lastModified(Date.from(domainDocument.lastModified()))
                .build();

        } catch (final ElementalDbServiceException | URISyntaxException e) {
            return toErrorResponse(e);
        }
    }

    @GET
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    public Response listDocuments(@HeaderParam("If-Modified-Since") @Nullable final Instant ifModifiedSince) {
        try {
            final Documents results = elemental.withConnection(null, connection ->
                elemental.transaction(connection, transaction ->
                    elemental.readCollection(DOCUMENTS_COLLECTION_PATH, connection, transaction, collection -> {

                        // Get a list of documents present in the database collection
                        try {
                            final List<Document> domainDocuments = new ArrayList<>();

                            final Iterator<DocumentImpl> documentIterator = collection.iterator(connection);
                            while (documentIterator.hasNext()) {
                                final DocumentImpl dbDocument = documentIterator.next();
                                domainDocuments.add(toDomainDocument(dbDocument));
                            }
                            return new Documents(domainDocuments);

                        } catch (final PermissionDeniedException | LockException e) {
                            throw new ElementalDbServiceException(e);
                        }
                    })
                )
            );

            @Nullable Instant lastModified = getLastModified(results);
            if (lastModified == null) {
                lastModified = Instant.now();
            }

            // check if the documents have been modified since the If-Modified-Since header
            if (ifModifiedSince != null) {
                if (ifModifiedSince.compareTo(lastModified) > 0) {
                    return Response.notModified()
                        .lastModified(Date.from(lastModified))
                        .build();
                }
            }

            return Response.ok()
                .lastModified(Date.from(lastModified))
                .entity(results)
                .build();

        } catch (final ElementalDbServiceException e) {
            return toErrorResponse(e);
        }
    }

    @GET
    @Path("{document-id}")
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    public Response getDocument(@PathParam("document-id") final String documentId, @HeaderParam("If-Modified-Since") @Nullable final Instant ifModifiedSince, @Context final HttpHeaders headers) {
        try {
            @Nullable final Tuple2<Long, Object> result = elemental.withConnection(elemental.systemUser(), connection ->
                elemental.transaction(connection, transaction ->
                    elemental.readCollection(DOCUMENTS_COLLECTION_PATH, connection, transaction, collection ->
                        elemental.readDocument(documentId, collection, connection, transaction, dbDocument -> {
                            if (dbDocument == null) {
                                return null;
                            } else {
                                final Object serialized = elemental.serializeDocument(dbDocument, toMediaTypeStrings(headers.getAcceptableMediaTypes()), connection, transaction);
                                return Tuple(dbDocument.getLastModified(), serialized);
                            }
                        })
                    )
                )
            );

            if (result == null) {
                return Response.status(HttpStatusCode.NOT_FOUND.code)
                    .entity(new Error(Integer.toString(HttpStatusCode.NOT_FOUND.code), "No such document: " + documentId))
                    .build();
            }

            final Instant lastModified = Instant.ofEpochMilli(result._1);

            // check if the document has been modified since the If-Modified-Since header
            if (ifModifiedSince != null) {
                if (ifModifiedSince.compareTo(lastModified) > 0) {
                    return Response.notModified()
                        .lastModified(Date.from(lastModified))
                        .build();
                }
            }

            return Response.ok()
                .lastModified(Date.from(lastModified))
                .entity(result._2)
                .build();

        } catch (final ElementalDbServiceException e) {
            return toErrorResponse(e);
        }
    }

    @PUT
    @Path("{document-id}")
    @Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    public Response replaceDocument(@PathParam("document-id") final String documentId, final String content, @Context final HttpHeaders headers) {
        try {
            @Nullable final Long lastModified = elemental.withConnection(elemental.systemUser(), connection ->
                elemental.transaction(connection, transaction ->
                    elemental.writeCollection(DOCUMENTS_COLLECTION_PATH, connection, transaction, collection -> {

                        // check that the document already exists
                        if (!elemental.hasDocument(documentId, collection, connection)) {
                            return null;
                        }

                        // replace the document
                        final URI documentUri = elemental.storeDocument(documentId, headers.getMediaType().toString(), new StringInputSource(content), collection, connection, transaction);
                        return elemental.readDocument(Paths.get(documentUri).getFileName().toString(), collection, connection, transaction, document ->
                            document.getLastModified()
                        );
                    })
                )
            );

            if (lastModified == null) {
                return Response.status(HttpStatusCode.NOT_FOUND.code)
                    .entity(new Error(Integer.toString(HttpStatusCode.NOT_FOUND.code), "No such document: " + documentId))
                    .build();
            }

            return Response.noContent()
                .lastModified(Date.from(Instant.ofEpochMilli(lastModified)))
                .build();

        } catch (final ElementalDbServiceException e) {
            return toErrorResponse(e);
        }
    }

    @DELETE
    @Path("{document-id}")
    public Response deleteDocument(@PathParam("document-id") final String documentId) {
        try {
            final boolean deleted = elemental.withConnection(elemental.systemUser(), connection ->
                elemental.transaction(connection, transaction ->
                    elemental.writeCollection(DOCUMENTS_COLLECTION_PATH, connection, transaction, collection -> {

                        // check that the document already exists
                        if (!elemental.hasDocument(documentId, collection, connection)) {
                            return false;
                        }

                        return elemental.writeDocument(documentId, collection, connection, transaction, document -> {
                            elemental.deleteDocument(document, collection, connection, transaction);
                            return true;
                        });
                    })
                )
            );

            if (!deleted) {
                return Response.status(HttpStatusCode.NOT_FOUND.code)
                    .entity(new Error(Integer.toString(HttpStatusCode.NOT_FOUND.code), "No such document: " + documentId))
                    .build();
            }

            return Response.noContent()
                .build();

        } catch (final ElementalDbServiceException e) {
            return toErrorResponse(e);
        }
    }

    @PATCH
    @Path("{document-id}")
    @Consumes(MediaType.APPLICATION_XML)
    public Response updateDocument(@PathParam("document-id") final String documentId, final String content) {

        try {
            final org.w3c.dom.Document contentDocument = elemental.withConnection(null, connection ->
                elemental.parseXml(connection, content)
            );

            final Element contentElement = contentDocument.getDocumentElement();
            if (!XUpdateProcessor.XUPDATE_NS.equals(contentElement.getNamespaceURI())) {
                return Response.status(HttpStatusCode.BAD_REQUEST.code)
                    .entity(new Error(Integer.toString(HttpStatusCode.BAD_REQUEST.code), "Content was not in the XUpdate namespace"))
                    .build();
            }

            if (!XUpdateProcessor.MODIFICATIONS.equals(contentElement.getLocalName())) {
                return Response.status(HttpStatusCode.BAD_REQUEST.code)
                    .entity(new Error(Integer.toString(HttpStatusCode.BAD_REQUEST.code), "Content was not an XUpdate modifications element"))
                    .build();
            }

            @Nullable final Tuple2<Long, Long> modifications = elemental.withConnection(elemental.systemUser(), connection ->
                elemental.transaction(connection, transaction ->
                    elemental.writeCollection(DOCUMENTS_COLLECTION_PATH, connection, transaction, collection -> {

                        // check that the document already exists
                        if (!elemental.hasDocument(documentId, collection, connection)) {
                            return null;
                        }

                        // patch the document with the XUpdate
                        final long modificationsProcessed = elemental.writeDocument(documentId, collection, connection, transaction, document -> {
                            final MutableDocumentSet target = new DefaultDocumentSet();
                            target.add(document);
                            return elemental.xupdate(content, target, connection, transaction);
                        });

                        final long lastModified = elemental.readDocument(documentId, collection, connection, transaction, document ->
                            document.getLastModified()
                        );

                        return Tuple(lastModified, modificationsProcessed);
                    })
                )
            );

            if (modifications == null) {
                return Response.status(HttpStatusCode.NOT_FOUND.code)
                    .entity(new Error(Integer.toString(HttpStatusCode.NOT_FOUND.code), "No such document: " + documentId))
                    .build();
            }

            return Response.noContent()
                .lastModified(Date.from(Instant.ofEpochMilli(modifications._1)))
                .header("X-XUpdate-Modifications", modifications._2)
                .build();

        } catch (final ElementalDbServiceException e) {
            return toErrorResponse(e);
        }
    }

    @PostConstruct
    void init() throws ElementalDbServiceException {
        // Create the DOCUMENTS_COLLECTION in the database if it does not yet exist
        final URI createdCollectionUri = elemental.withConnection(elemental.systemUser(), connection ->
            elemental.transaction(connection, transaction ->
                elemental.writeCollection(DOCUMENTS_COLLECTION_PATH, connection, transaction, collection -> {
                    try {
                        return collection.getURI().getXmldbURI();
                    } catch (final IllegalArgumentException e) {
                        throw new ElementalDbServiceException(e);
                    }
                })
            )
        );

        if (!DOCUMENTS_COLLECTION_PATH.equals(createdCollectionUri)) {
            throw new ElementalDbServiceException("Unable to create database collection: " + DOCUMENTS_COLLECTION_PATH);
        }
    }

    private List<String> toMediaTypeStrings(final List<MediaType> mediaTypes) {
        final List<String> results = new ArrayList<>();
        for (final MediaType mediaType : mediaTypes) {
            results.add(mediaType.toString());
        }
        return results;
    }

    private Document toDomainDocument(final DocumentImpl dbDocument) {
        return new Document(
            dbDocument.getURI().lastSegment().toString(),
            Instant.ofEpochMilli(dbDocument.getCreated()),
            Instant.ofEpochMilli(dbDocument.getLastModified()),
            dbDocument.getPermissions().getOwner().getName(),
            dbDocument.getPermissions().getGroup().getName(),
            dbDocument.getPermissions().toString()
        );
    }

    private Response toErrorResponse(final Throwable throwable) {
        if (throwable instanceof ElementalDbServiceException) {
            final Throwable cause = throwable.getCause();

            if (cause instanceof SAXException || cause instanceof XPathException) {
                return Response.status(HttpStatusCode.BAD_REQUEST.code)
                    .entity(toDomainError(Integer.toString(HttpStatusCode.BAD_REQUEST.code), cause))
                    .build();

            } else if (cause instanceof PermissionDeniedException) {
                return Response.status(HttpStatusCode.FORBIDDEN.code)
                    .entity(toDomainError(Integer.toString(HttpStatusCode.INTERNAL_SERVER_ERROR.code), cause))
                    .build();
            }
        }

        return Response.serverError()
            .entity(toDomainError(Integer.toString(HttpStatusCode.INTERNAL_SERVER_ERROR.code), throwable))
            .build();
    }

    private Error toDomainError(final String code, final Throwable throwable) {
        return new Error(code, throwable.getMessage());
    }

    private @Nullable Instant getLastModified(final Documents documents) {
        Instant lastModified = null;
        for (final Document document : documents.documents()) {
            // get the most recent last modified date of the documents
            if (lastModified == null) {
                lastModified = document.lastModified();
            } else {
                if (lastModified.compareTo(document.lastModified()) < 0) {
                    lastModified = document.lastModified();
                }
            }
        }
        return lastModified;
    }
}

package com.evolvedbinary.dasp.elemental;

import com.evolvedbinary.j8fu.function.Function2E;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.exist.EXistException;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.persistent.*;
import org.exist.security.PermissionDeniedException;
import org.exist.security.Subject;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.storage.lock.ManagedCollectionLock;
import org.exist.storage.txn.TransactionException;
import org.exist.storage.txn.Txn;
import org.exist.util.*;
import org.exist.util.serializer.XQuerySerializer;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.ValueSequence;
import org.exist.xupdate.Modification;
import org.exist.xupdate.XUpdateProcessor;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.annotation.Nullable;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;


@Singleton
public class ElementalDbService {

    private MimeTable mimeTable;
    private BrokerPool brokerPool;

    @PostConstruct
    void init() throws EXistException, DatabaseConfigurationException, URISyntaxException {
        final Path configFile = Paths.get(getClass().getResource("elemental.conf.xml").toURI());
        final Configuration configuration = new Configuration(configFile.toAbsolutePath().toString());
        BrokerPool.configure(1, 20, configuration);
        this.brokerPool = BrokerPool.getInstance();
        this.mimeTable = MimeTable.getInstance();
    }

    @PreDestroy
    void shutdown() {
        this.mimeTable = null;
        brokerPool.shutdown();
        brokerPool = null;
    }

    public <T, E extends Throwable> T withConnection(@Nullable final Subject user, final Function2E<DBBroker, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        try (final DBBroker dbBroker = brokerPool.get(Optional.ofNullable(user))) {
            return operation.apply(dbBroker);
        } catch (final EXistException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    public Subject systemUser() {
        return brokerPool.getSecurityManager().getSystemSubject();
    }

    public <T, E extends Throwable> T transaction(final DBBroker broker, final Function2E<Txn, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        try (final Txn transaction = broker.getBrokerPool().getTransactionManager().beginTransaction()) {
            final T result = operation.apply(transaction);
            transaction.commit();
            return result;
        } catch (final TransactionException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    public <T, E extends Throwable> T readCollection(final URI collectionUri, final DBBroker connection, final Txn transaction, final Function2E<org.exist.collections.Collection, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        return withCollection(collectionUri, connection, transaction, collection -> {
            try (final ManagedCollectionLock collectionLock = connection.getBrokerPool().getLockManager().acquireCollectionReadLock(collection.getURI())) {
                return operation.apply(collection);
            } catch (final LockException e) {
                throw new ElementalDbServiceException(e);
            }
        });
    }

    public <T, E extends Throwable> T writeCollection(final URI collectionUri, final DBBroker connection, final Txn transaction, final Function2E<org.exist.collections.Collection, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        return withCollection(collectionUri, connection, transaction, collection -> {
            try (final ManagedCollectionLock collectionLock = connection.getBrokerPool().getLockManager().acquireCollectionWriteLock(collection.getURI())) {
                return operation.apply(collection);
            } catch (final LockException e) {
                throw new ElementalDbServiceException(e);
            }
        });
    }

    private <T, E extends Throwable> T withCollection(final URI collectionUri, final DBBroker connection, final Txn transaction, final Function2E<org.exist.collections.Collection, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        try (final org.exist.collections.Collection collection = connection.getOrCreateCollection(transaction, XmldbURI.xmldbUriFor(collectionUri))) {
            return operation.apply(collection);
        } catch (final URISyntaxException | PermissionDeniedException | IOException | TriggerException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    public boolean hasDocument(final String documentName, final org.exist.collections.Collection collection, final DBBroker broker) throws ElementalDbServiceException {
        try {
            return collection.hasDocument(broker, XmldbURI.create(documentName));
        } catch (final PermissionDeniedException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    public URI storeDocument(final String mediaType, final InputSource content, final org.exist.collections.Collection collection, final DBBroker connection, final Txn transaction) throws ElementalDbServiceException {
        final UUID randomUuid = UUID.randomUUID();
        final String ext = mimeTable.getPreferredExtension(mediaType);
        final String documentName = randomUuid + ext;
        return storeDocument(documentName, mediaType, content, collection, connection, transaction);
    }

    public URI storeDocument(final String documentName, final String mediaType, final InputSource content, final org.exist.collections.Collection collection, final DBBroker connection, final Txn transaction) throws ElementalDbServiceException {
        final MimeTable mimeTable = MimeTable.getInstance();
        try {
            connection.storeDocument(transaction, XmldbURI.create(documentName), content, mimeTable.getContentType(mediaType), collection);
            return collection.getURI().append(documentName).getXmldbURI();
        } catch (final EXistException | PermissionDeniedException | LockException | SAXException | IOException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    public void deleteDocument(final DocumentImpl document, final org.exist.collections.Collection collection, final DBBroker connection, final Txn transaction) throws ElementalDbServiceException {
        try {
            collection.removeResource(transaction, connection, document);
        } catch (final PermissionDeniedException | LockException | IOException | TriggerException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    public <T, E extends Throwable> T readDocument(final String documentName, final org.exist.collections.Collection collection, final DBBroker connection, final Txn transaction, final Function2E<DocumentImpl, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        return withDocument(documentName, Lock.LockMode.READ_LOCK, collection, connection, transaction, operation);
    }

    public <T, E extends Throwable> T writeDocument(final String documentName, final org.exist.collections.Collection collection, final DBBroker connection, final Txn transaction, final Function2E<DocumentImpl, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        return withDocument(documentName, Lock.LockMode.WRITE_LOCK, collection, connection, transaction, operation);
    }

    private <T, E extends Throwable> T withDocument(final String documentName, final Lock.LockMode documentLockMode, final org.exist.collections.Collection collection, final DBBroker connection, final Txn transaction, final Function2E<DocumentImpl, T, ElementalDbServiceException, E> operation) throws ElementalDbServiceException, E {
        @Nullable LockedDocument lockedDocument = null;
        try {
             lockedDocument = collection.getDocumentWithLock(connection, XmldbURI.create(documentName), documentLockMode);
             @Nullable DocumentImpl document = null;
             if (lockedDocument != null) {
                 document = lockedDocument.getDocument();
             }
             return operation.apply(document);
        } catch (final PermissionDeniedException | LockException e) {
            throw new ElementalDbServiceException(e);
        } finally {
            if (lockedDocument != null) {
                lockedDocument.close();
            }
        }
    }

    public Object serializeDocument(final DocumentImpl document, final List<String> acceptableMediaTypes, final DBBroker connection, final Txn transaction) throws ElementalDbServiceException {
        if (document instanceof BinaryDocument) {
            return serializeBinaryDocument((BinaryDocument) document, transaction);
        } else {
            return serializeXmlDocument(document, acceptableMediaTypes, connection);
        }
    }

    private byte[] serializeBinaryDocument(final BinaryDocument binaryDocument, final Txn transaction) throws ElementalDbServiceException {
        try (final UnsynchronizedByteArrayOutputStream os = new UnsynchronizedByteArrayOutputStream((int) binaryDocument.getContentLength());
             final InputStream is = brokerPool.getBlobStore().get(transaction, binaryDocument.getBlobId())) {
            IOUtils.copy(is, os);
            return os.toByteArray();
        } catch (final IOException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    private String serializeXmlDocument(final DocumentImpl xmlDocument, final List<String> acceptableMediaTypes, final DBBroker connection) throws ElementalDbServiceException {
        try (final Writer writer = new StringWriter()) {

            final Properties serializationProperties = new Properties();
            if (acceptableMediaTypes.contains(MediaType.APPLICATION_XML)) {
                serializationProperties.put(OutputKeys.METHOD, "xml");

            } else if (acceptableMediaTypes.contains(MediaType.APPLICATION_JSON)) {
                serializationProperties.put(OutputKeys.METHOD, "json");

            } else {
                throw new ElementalDbServiceException("No suitable serialization for acceptableMediaTypes");
            }

            final XQuerySerializer xquerySerializer = new XQuerySerializer(connection, serializationProperties, writer);
            final ValueSequence sequence = new ValueSequence(1);
            sequence.add(new NodeProxy(xmlDocument));
            xquerySerializer.serialize(sequence);

            return writer.toString();
        } catch (final SAXException | XPathException | IOException e) {
            throw new ElementalDbServiceException(e);
        }
    }

    public Document parseXml(final DBBroker connection, final String xml) throws ElementalDbServiceException {
        final XMLReaderPool xmlReaderPool = connection.getBrokerPool().getXmlReaderPool();
        final XMLReader xmlReader = xmlReaderPool.borrowXMLReader();
        try {

            final DocumentBuilderReceiver documentBuilderReceiver = new DocumentBuilderReceiver();
            xmlReader.setContentHandler(documentBuilderReceiver);
            xmlReader.parse(new StringInputSource(xml));
            return documentBuilderReceiver.getDocument();

        } catch (final SAXException | IOException e) {
            throw new ElementalDbServiceException(e);

        } finally {
            xmlReaderPool.returnObject(xmlReader);
        }
    }

    public long xupdate(final String xupdate, final DocumentSet target, final DBBroker connection, final Txn transaction) throws ElementalDbServiceException {
        try (final Reader reader = new StringReader(xupdate)) {
            final XUpdateProcessor processor = new XUpdateProcessor(connection, target);
            long mods = 0;

            final Modification modifications[] = processor.parse(new InputSource(reader));
            for (final Modification modification : modifications) {
                mods += modification.process(transaction);
            }

            return mods;

        } catch (final IOException | ParserConfigurationException | SAXException | PermissionDeniedException | LockException | EXistException | XPathException e) {
            throw new ElementalDbServiceException(e);
        }
    }

}

package de.bwaldvogel.mongo.backend.memory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.log4j.Logger;
import org.bson.BSONObject;
import org.bson.BasicBSONObject;
import org.jboss.netty.channel.Channel;

import com.mongodb.BasicDBObject;

import de.bwaldvogel.mongo.backend.Constants;
import de.bwaldvogel.mongo.backend.MongoCollection;
import de.bwaldvogel.mongo.backend.Utils;
import de.bwaldvogel.mongo.backend.memory.index.UniqueIndex;
import de.bwaldvogel.mongo.exception.MongoServerError;
import de.bwaldvogel.mongo.exception.MongoServerException;
import de.bwaldvogel.mongo.exception.MongoSilentServerException;
import de.bwaldvogel.mongo.exception.NoSuchCommandException;
import de.bwaldvogel.mongo.wire.message.MongoDelete;
import de.bwaldvogel.mongo.wire.message.MongoInsert;
import de.bwaldvogel.mongo.wire.message.MongoQuery;
import de.bwaldvogel.mongo.wire.message.MongoUpdate;

public class MemoryDatabase extends CommonDatabase {

    private static final Logger log = Logger.getLogger(MemoryDatabase.class);

    private Map<String, MongoCollection> collections = new HashMap<String, MongoCollection>();
    private Map<Channel, MongoServerError> lastExceptions = new HashMap<Channel, MongoServerError>();
    private Map<Channel, BSONObject> lastUpdates = new HashMap<Channel, BSONObject>();

    private MemoryBackend backend;

    private MongoCollection namespaces;
    private MongoCollection indexes;

    public MemoryDatabase(MemoryBackend backend, String databaseName) throws MongoServerException {
        super(databaseName);
        this.backend = backend;
        namespaces = new MemoryNamespacesCollection(getDatabaseName());
        collections.put(namespaces.getCollectionName(), namespaces);

        indexes = new MemoryIndexesCollection(getDatabaseName());
        addNamespace(indexes);
    }

    private synchronized MongoCollection resolveCollection(String collectionName, boolean throwIfNotFound)
            throws MongoServerException {
        checkCollectionName(collectionName);
        MongoCollection collection = collections.get(collectionName);
        if (collection == null && throwIfNotFound) {
            throw new MongoServerException("ns not found");
        }
        return collection;
    }

    private void checkCollectionName(String collectionName) throws MongoServerException {

        if (collectionName.length() > Constants.MAX_NS_LENGTH)
            throw new MongoServerError(10080, "ns name too long, max size is " + Constants.MAX_NS_LENGTH);

        if (collectionName.isEmpty())
            throw new MongoServerError(16256, "Invalid ns [" + collectionName + "]");
    }

    public MongoServerError getLastException(int clientId) {
        return lastExceptions.get(Integer.valueOf(clientId));
    }

    @Override
    public boolean isEmpty() {
        return collections.isEmpty();
    }

    @Override
    public Iterable<BSONObject> handleQuery(MongoQuery query) throws MongoServerException {
        String collectionName = query.getCollectionName();
        MongoCollection collection = resolveCollection(collectionName, false);
        if (collection == null) {
            return Collections.emptyList();
        }
        return collection.handleQuery(query.getQuery(), query.getNumberToSkip(), query.getNumberToReturn(),
                query.getReturnFieldSelector());
    }

    @Override
    public void handleClose(Channel channel) {
        lastExceptions.remove(channel);
        lastUpdates.remove(channel);
    }

    @Override
    public void handleInsert(MongoInsert insert) throws MongoServerException {
        lastUpdates.remove(insert.getChannel());
        try {
            String collectionName = insert.getCollectionName();
            if (collectionName.equals(indexes.getCollectionName())) {
                for (BSONObject indexDescription : insert.getDocuments()) {
                    addIndex(indexDescription);
                }
                return;
            }
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(16459, "attempt to insert in system namespace");
            }
            MongoCollection collection = resolveCollection(collectionName, false);
            if (collection == null) {
                collection = createCollection(collectionName);
            }
            int n = collection.handleInsert(insert);
            lastUpdates.put(insert.getChannel(), new BasicBSONObject("n", Integer.valueOf(n)));
        } catch (MongoServerError e) {
            log.error("failed to insert " + insert, e);
            lastExceptions.put(insert.getChannel(), e);
        }
    }

    private void addIndex(BSONObject indexDescription) throws MongoServerException {

        String ns = indexDescription.get("ns").toString();
        int index = ns.indexOf('.');
        String collectionName = ns.substring(index + 1);
        MongoCollection collection = resolveCollection(collectionName, false);
        if (collection == null) {
            collection = createCollection(collectionName);
        }

        indexes.addDocument(indexDescription);

        BSONObject key = (BSONObject) indexDescription.get("key");
        if (key.keySet().equals(Collections.singleton("_id"))) {
            boolean ascending = Utils.normalizeValue(key.get("_id")).equals(Double.valueOf(1.0));
            collection.addIndex(new UniqueIndex("_id", ascending));
        } else {
            // non-id indexes not yet implemented
            // we can ignore that for a moment since it will not break the
            // functionality
        }
    }

    private MongoCollection createCollection(String collectionName) throws MongoServerException {
        checkCollectionName(collectionName);
        if (collectionName.contains("$")) {
            throw new MongoServerError(10093, "cannot insert into reserved $ collection");
        }
        MongoCollection collection = new MemoryCollection(getDatabaseName(), collectionName, "_id");
        addNamespace(collection);

        BSONObject indexDescription = new BasicBSONObject();
        indexDescription.put("name", "_id_");
        indexDescription.put("ns", collection.getFullName());
        indexDescription.put("key", new BasicBSONObject("_id", Integer.valueOf(1)));
        addIndex(indexDescription);

        return collection;
    }

    protected void addNamespace(MongoCollection collection) throws MongoServerException {
        collections.put(collection.getCollectionName(), collection);
        namespaces.addDocument(new BasicDBObject("name", collection.getFullName()));
    }

    @Override
    public void handleDelete(MongoDelete delete) throws MongoServerException {
        lastUpdates.remove(delete.getChannel());
        try {
            String collectionName = delete.getCollectionName();
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(12050, "cannot delete from system namespace");
            }
            MongoCollection collection = resolveCollection(collectionName, false);
            int n;
            if (collection == null) {
                n = 0;
            } else {
                n = collection.handleDelete(delete);
            }
            lastUpdates.put(delete.getChannel(), new BasicBSONObject("n", Integer.valueOf(n)));
        } catch (MongoServerError e) {
            log.error("failed to delete " + delete, e);
            lastExceptions.put(delete.getChannel(), e);
        }
    }

    @Override
    public void handleUpdate(MongoUpdate update) throws MongoServerException {
        lastUpdates.remove(update.getChannel());
        try {
            String collectionName = update.getCollectionName();

            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(10156, "cannot update system collection");
            }

            MongoCollection collection = resolveCollection(collectionName, false);
            if (collection == null) {
                collection = createCollection(collectionName);
            }
            BSONObject result = collection.handleUpdate(update);
            lastUpdates.put(update.getChannel(), result);
        } catch (MongoServerError e) {
            log.error("failed to update " + update, e);
            lastExceptions.put(update.getChannel(), e);
        }
    }

    @Override
    public BSONObject handleCommand(Channel channel, String command, BSONObject query) throws MongoServerException {
        if (command.equalsIgnoreCase("count")) {
            return commandCount(command, query);
        } else if (command.equalsIgnoreCase("getlasterror")) {
            return commandGetLastError(channel, command, query);
        } else if (command.equalsIgnoreCase("distinct")) {
            String collectionName = query.get(command).toString();
            MongoCollection collection = resolveCollection(collectionName, true);
            return collection.handleDistinct(query);
        } else if (command.equalsIgnoreCase("drop")) {
            return commandDrop(query);
        } else if (command.equalsIgnoreCase("dropDatabase")) {
            return commandDropDatabase();
        } else if (command.equalsIgnoreCase("dbstats")) {
            return commandDatabaseStats();
        } else if (command.equalsIgnoreCase("collstats")) {
            String collectionName = query.get("collstats").toString();
            MongoCollection collection = resolveCollection(collectionName, true);
            return collection.getStats();
        } else if (command.equalsIgnoreCase("validate")) {
            String collectionName = query.get("validate").toString();
            MongoCollection collection = resolveCollection(collectionName, true);
            return collection.validate();
        } else if (command.equalsIgnoreCase("findAndModify")) {
            String collectionName = query.get(command).toString();
            MongoCollection collection = resolveCollection(collectionName, false);
            if (collection == null) {
                collection = createCollection(collectionName);
            }
            return collection.findAndModify(query);
        } else {
            log.error("unknown query: " + query);
        }
        throw new NoSuchCommandException(command);
    }

    private BSONObject commandDatabaseStats() throws MongoServerException {
        BSONObject response = new BasicBSONObject("db", getDatabaseName());
        response.put("collections", Integer.valueOf(namespaces.count()));

        long indexSize = 0;
        long objects = 0;
        long dataSize = 0;
        double averageObjectSize = 0;

        for (MongoCollection collection : collections.values()) {
            BSONObject stats = collection.getStats();
            objects += ((Number) stats.get("count")).longValue();
            dataSize += ((Number) stats.get("size")).longValue();

            BSONObject indexSizes = (BSONObject) stats.get("indexSize");
            for (String indexName : indexSizes.keySet()) {
                indexSize += ((Number) indexSizes.get(indexName)).longValue();
            }

        }
        if (objects > 0) {
            averageObjectSize = dataSize / ((double) objects);
        }
        response.put("objects", Long.valueOf(objects));
        response.put("avgObjSize", Double.valueOf(averageObjectSize));
        response.put("dataSize", Long.valueOf(dataSize));
        response.put("storageSize", Long.valueOf(0));
        response.put("numExtents", Integer.valueOf(0));
        response.put("indexes", Integer.valueOf(indexes.count()));
        response.put("indexSize", Long.valueOf(indexSize));
        response.put("fileSize", Integer.valueOf(0));
        response.put("nsSizeMB", Integer.valueOf(0));
        Utils.markOkay(response);
        return response;
    }

    private BSONObject commandDropDatabase() {
        backend.dropDatabase(this);
        BSONObject response = new BasicBSONObject("dropped", getDatabaseName());
        Utils.markOkay(response);
        return response;
    }

    private BSONObject commandDrop(BSONObject query) throws MongoServerException {
        String collectionName = query.get("drop").toString();
        MongoCollection collection = collections.remove(collectionName);

        if (collection == null) {
            throw new MongoSilentServerException("ns not found");
        }
        BSONObject response = new BasicBSONObject();
        namespaces.removeDocument(new BasicBSONObject("name", collection.getFullName()));
        response.put("nIndexesWas", Integer.valueOf(collection.getNumIndexes()));
        response.put("ns", collection.getFullName());
        Utils.markOkay(response);
        return response;

    }

    private BSONObject commandGetLastError(Channel channel, String command, BSONObject query)
            throws MongoServerException {
        Iterator<String> it = query.keySet().iterator();
        String cmd = it.next();
        if (!cmd.equals(command))
            throw new IllegalStateException();
        if (it.hasNext()) {
            String subCommand = it.next();
            if (!subCommand.equals("w")) {
                throw new MongoServerException("unknown subcommand: " + subCommand);
            }
        }

        BSONObject result = new BasicBSONObject();
        Utils.markOkay(result);
        if (lastExceptions != null) {
            MongoServerError ex = lastExceptions.remove(channel);
            if (ex != null) {
                BSONObject obj = new BasicBSONObject("err", ex.getMessage());
                obj.put("code", Integer.valueOf(ex.getCode()));
                obj.put("connectionId", channel.getId());
                Utils.markOkay(obj);
                return obj;
            }

            BSONObject lastUpdate = lastUpdates.remove(channel);
            if (lastUpdate != null) {
                result.putAll(lastUpdate);
            }
        }

        return result;
    }

    private BSONObject commandCount(String command, BSONObject query) throws MongoServerException {
        String collection = query.get(command).toString();
        BSONObject response = new BasicBSONObject();
        MongoCollection coll = collections.get(collection);
        if (coll == null) {
            response.put("missing", Boolean.TRUE);
            response.put("n", Integer.valueOf(0));
        } else {
            response.put("n", Integer.valueOf(coll.count((BSONObject) query.get("query"))));
        }
        Utils.markOkay(response);
        return response;
    }
}

/*
 * RESTHeart - the data Repository API server
 * Copyright (C) SoftInstigate Srl
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.db;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import java.util.Objects;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;
import org.restheart.utils.HttpStatus;

/**
 * This DAO takes care of changes to metadata for binary files that have been
 * created using GridFS.
 *
 * @author Nath Papadacis {@literal <nath@thirststudios.co.uk>}
 */
public class FileMetadataDAO implements FileMetadataRepository {

    private final MongoClient client;

    public FileMetadataDAO() {
        client = MongoDBClientSingleton.getInstance().getClient();
    }

    @Override
    public OperationResult updateMetadata(
            final String dbName,
            final String collName,
            final Object documentId,
            final BsonDocument filter,
            final BsonDocument shardKeys,
            final BsonDocument newContent,
            final String requestEtag,
            final boolean patching,
            final boolean checkEtag) {
        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<BsonDocument> mcoll = mdb.getCollection(collName, BsonDocument.class);

        // genereate new etag
        ObjectId newEtag = new ObjectId();

        final BsonDocument content = DAOUtils.validContent(newContent);
        content.get("metadata", new BsonDocument()).asDocument().put("_etag", new BsonObjectId(newEtag));

        OperationResult updateResult = DAOUtils.updateMetadata(
                mcoll,
                documentId,
                filter,
                shardKeys,
                content,
                patching);

        BsonDocument oldDocument = updateResult.getOldData();

        if (patching) {
            if (oldDocument == null) { // Attempted an insert of a new doc.
                return new OperationResult(
                        updateResult.getHttpCode() > 0
                        ? updateResult.getHttpCode()
                        : HttpStatus.SC_CONFLICT, newEtag, null, updateResult.getNewData());
            } else if (checkEtag) {
                // check the old etag (in case restore the old document version)
                return optimisticCheckEtag(
                        mcoll,
                        shardKeys,
                        oldDocument,
                        newEtag,
                        requestEtag,
                        HttpStatus.SC_OK);
            } else {
                BsonDocument newDocument = mcoll.find(
                        eq("_id", documentId)).first();

                return new OperationResult(updateResult.getHttpCode() > 0
                        ? updateResult.getHttpCode()
                        : HttpStatus.SC_OK, newEtag, oldDocument, newDocument);
            }
        } else if (oldDocument != null && checkEtag) { // update
            // check the old etag (in case restore the old document)
            return optimisticCheckEtag(
                    mcoll,
                    shardKeys,
                    oldDocument,
                    newEtag,
                    requestEtag,
                    HttpStatus.SC_OK);
        } else if (oldDocument != null) {  // update
            BsonDocument newDocument = mcoll.find(
                    eq("_id", documentId)).first();

            return new OperationResult(
                    updateResult.getHttpCode() > 0
                    ? updateResult.getHttpCode()
                    : HttpStatus.SC_OK, newEtag, oldDocument, newDocument);
        } else { // Attempted an insert of a new doc.
            return new OperationResult(
                    updateResult.getHttpCode() > 0
                    ? updateResult.getHttpCode()
                    : HttpStatus.SC_CONFLICT, newEtag, null, updateResult.getNewData());
        }
    }

    private OperationResult optimisticCheckEtag(
            final MongoCollection<BsonDocument> coll,
            final BsonDocument shardKeys,
            final BsonDocument oldDocument,
            final Object newEtag,
            final String requestEtag,
            final int httpStatusIfOk) {

        BsonValue oldEtag = oldDocument.get("metadata", new BsonDocument()).asDocument().get("_etag");

        if (oldEtag != null && requestEtag == null) {
            // oops, we need to restore old document
            DAOUtils.restoreDocument(
                    coll,
                    oldDocument.get("_id"),
                    shardKeys,
                    oldDocument,
                    newEtag,
                    "metadata._etag");

            return new OperationResult(
                    HttpStatus.SC_CONFLICT, oldEtag, oldDocument, null);
        }

        BsonValue _requestEtag;

        if (ObjectId.isValid(requestEtag)) {
            _requestEtag = new BsonObjectId(new ObjectId(requestEtag));
        } else {
            // restheart generates ObjectId etags, but here we support
            // strings as well
            _requestEtag = new BsonString(requestEtag);
        }

        if (Objects.equals(_requestEtag, oldEtag)) {
            BsonDocument newDocument = coll.find(
                    eq("_id", oldDocument.get("_id"))).first();

            return new OperationResult(
                    httpStatusIfOk, newEtag, oldDocument, newDocument);
        } else {
            // oops, we need to restore old document
            DAOUtils.restoreDocument(
                    coll,
                    oldDocument.get("_id"),
                    shardKeys,
                    oldDocument,
                    newEtag,
                    "metadata._etag");

            return new OperationResult(
                    HttpStatus.SC_PRECONDITION_FAILED,
                    oldEtag,
                    oldDocument,
                    null);
        }
    }
}

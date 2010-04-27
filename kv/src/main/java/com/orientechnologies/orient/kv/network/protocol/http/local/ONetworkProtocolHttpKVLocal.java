/*
 * Copyright 1999-2010 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.kv.network.protocol.http.local;

import java.util.HashMap;
import java.util.Map;

import com.orientechnologies.orient.core.db.record.ODatabaseBinary;
import com.orientechnologies.orient.core.engine.memory.OEngineMemory;
import com.orientechnologies.orient.kv.OSharedDatabase;
import com.orientechnologies.orient.kv.network.protocol.http.ONetworkProtocolHttpKV;
import com.orientechnologies.orient.kv.network.protocol.http.partitioned.ODistributedException;
import com.orientechnologies.orient.kv.network.protocol.http.partitioned.OServerClusterMember;
import com.orientechnologies.orient.server.OServerMain;
import com.orientechnologies.orient.server.config.OServerStorageConfiguration;

public class ONetworkProtocolHttpKVLocal extends ONetworkProtocolHttpKV {
	private static Map<String, Map<String, Map<String, String>>>	memoryDatabases	= new HashMap<String, Map<String, Map<String, String>>>();

	static {
		// CREATE IN-MEMORY DATABASES EARLY
		for (OServerStorageConfiguration stg : OServerMain.server().getConfiguration().storages) {
			if (stg.path.startsWith(OEngineMemory.NAME)) {
				ODatabaseBinary db = new ODatabaseBinary(stg.path);

				// CREATE AND PUT IN THE MEMORY MAPTABLE TO AVOID LOCKING (IT'S THREAD SAFE)
				db.create();
				OServerMain.server().getMemoryDatabases().put(stg.name, db);
			}
		}
	}

	@Override
	protected Map<String, String> getBucket(final String dbName, final String iBucketName) {
		ODatabaseBinary db = null;

		// CHECK FOR IN-MEMORY DB
		db = (ODatabaseBinary) OServerMain.server().getMemoryDatabases().get(dbName);
		if (db != null)
			return getBucketFromMemory(dbName, iBucketName);
		else
			return getBucketFromDatabase(dbName, iBucketName);
	}

	protected Map<String, String> getBucketFromDatabase(final String dbName, final String iBucketName) {
		ODatabaseBinary db = null;

		try {
			db = OSharedDatabase.acquireDatabase(dbName);

			return OServerClusterMember.getDictionaryBucket(db, iBucketName);

		} catch (Exception e) {
			throw new ODistributedException("Error on retrieving bucket '" + iBucketName + "' in database: " + dbName, e);
		} finally {

			if (db != null)
				OSharedDatabase.releaseDatabase(db);
		}
	}

	protected Map<String, String> getBucketFromMemory(final String dbName, final String iBucketName) {
		Map<String, Map<String, String>> db = memoryDatabases.get(dbName);
		if (db == null) {
			db = new HashMap<String, Map<String, String>>();
			memoryDatabases.put(dbName, db);
		}

		Map<String, String> bucket = db.get(iBucketName);
		if (bucket == null) {
			bucket = new HashMap<String, String>();
			db.put(iBucketName, bucket);
		}
		return bucket;
	}

	@Override
	protected String getKey(final String iDbBucketKey) {
		final String[] parts = getDbBucketKey(iDbBucketKey, 2);
		if (parts.length > 2)
			return parts[2];
		return null;
	}
}
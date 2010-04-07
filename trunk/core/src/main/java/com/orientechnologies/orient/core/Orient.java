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
package com.orientechnologies.orient.core;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.orientechnologies.common.concur.resource.OSharedResource;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.engine.OEngine;
import com.orientechnologies.orient.core.engine.local.OEngineLocal;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;

public class Orient extends OSharedResource {
	public static final String						URL_SYNTAX		= "<engine>:<db-type>:<db-name>[?<db-param>=<db-value>[&]]*";

	protected Map<String, OEngine>				engines				= new HashMap<String, OEngine>();
	protected Map<String, OStorageLocal>	storages			= new HashMap<String, OStorageLocal>();
	protected OrientShutdownHook					shutdownHook	= new OrientShutdownHook();
	protected volatile boolean						active				= false;

	protected static Orient								instance			= new Orient();

	protected Orient() {
		// REGISTER THE EMBEDDED ENGINE
		registerEngine(new OEngineLocal());

		active = true;
	}

	public OStorage getStorage(String iURL) {
		if (iURL == null || iURL.length() == 0)
			throw new IllegalArgumentException("URL missed");

		// SEARCH FOR ENGINE
		int pos = iURL.indexOf(':');
		if (pos <= 0)
			throw new OConfigurationException("Error on opening database: the engine was not specified. Syntax is: " + URL_SYNTAX
					+ ". URL was: " + iURL);

		String engineName = iURL.substring(0, pos);

		try {
			acquireExclusiveLock();

			OEngine engine = engines.get(engineName.toLowerCase());

			if (engine == null)
				throw new OConfigurationException("Error on opening database: the engine '" + engineName + "' was not found. URL was: "
						+ iURL);

			// SEARCH FOR DB-NAME
			iURL = iURL.substring(pos + 1);
			pos = iURL.indexOf('?');

			Map<String, String> parameters = null;
			String dbName = null;
			if (pos > 0) {
				dbName = iURL.substring(0, pos);
				iURL = iURL.substring(pos + 1);

				// PARSE PARAMETERS
				parameters = new HashMap<String, String>();
				String[] pairs = iURL.split("&");
				String[] kv;
				for (String pair : pairs) {
					kv = pair.split("=");
					if (kv.length < 2)
						throw new OConfigurationException("Error on opening database: the parameter has no value. Syntax is: " + URL_SYNTAX
								+ ". URL was: " + iURL);
					parameters.put(kv[0], kv[1]);
				}
			} else
				dbName = iURL;

			return engine.getStorage(dbName, parameters);

		} finally {
			releaseExclusiveLock();
		}
	}

	public OStorageLocal accessToLocalStorage(String iURL, String iMode) throws IOException {
		try {
			acquireExclusiveLock();

			OStorageLocal storage = storages.get(iURL);
			if (storage == null) {
				storage = new OStorageLocal(iURL, iURL, iMode);
				storages.put(iURL, storage);
			}

			return storage;

		} finally {
			releaseExclusiveLock();
		}
	}

	public void registerEngine(OEngine iEngine) {
		try {
			acquireExclusiveLock();

			engines.put(iEngine.getName(), iEngine);

		} finally {
			releaseExclusiveLock();
		}
	}

	public Set<String> getEngines() {
		try {
			acquireSharedLock();

			return Collections.unmodifiableSet(engines.keySet());

		} finally {
			releaseSharedLock();
		}
	}

	public Collection<OStorageLocal> getStorages() {
		try {
			acquireSharedLock();

			return Collections.unmodifiableCollection(storages.values());

		} finally {
			releaseSharedLock();
		}
	}

	public void shutdown() {
		try {
			acquireExclusiveLock();

			if (!active)
				return;

			OLogManager.instance().info(this, "Orient Engine is shutdowning...");

			// CLOSE ALL THE STORAGES
			for (OStorage stg : storages.values()) {
				OLogManager.instance().info(this, "Shutdowning storage: " + stg.getName() + "...");
				stg.close();
			}
			active = false;

			OLogManager.instance().info(this, "Orient Engine shutdown complete");

		} finally {
			releaseExclusiveLock();
		}
	}

	public void removeShutdownHook() {
		Runtime.getRuntime().removeShutdownHook(shutdownHook);
	}

	public static Orient instance() {
		return instance;
	}
}
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
package com.orientechnologies.orient.client.remote;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryProtocol;
import com.orientechnologies.orient.enterprise.channel.distributed.OChannelDistributedProtocol;

/**
 * Remote administration class of OrientDB Server instances.
 */
public class OServerAdmin {
	private OStorageRemote	storage;

	/**
	 * Creates the object passing a remote URL to connect.
	 * 
	 * @param iURL
	 *          URL to connect. It supports only the "remote" storage type.
	 * @throws IOException
	 */
	public OServerAdmin(String iURL) throws IOException {
		if (iURL.startsWith(OEngineRemote.NAME))
			iURL = iURL.substring(OEngineRemote.NAME.length() + 1);

		if (!iURL.contains("/"))
			iURL += "/";

		storage = new OStorageRemote(iURL, "");
	}

	/**
	 * Creates the object starting from an existent remote storage.
	 * 
	 * @param iStorage
	 */
	public OServerAdmin(final OStorageRemote iStorage) {
		storage = iStorage;
	}

	public OServerAdmin connect(final String iUserName, final String iUserPassword) throws IOException {
		storage.parseServerURLs();
		storage.createNetworkConnection();

		try {
			try {
				storage.beginRequest(OChannelBinaryProtocol.REQUEST_CONNECT);
				storage.getNetwork().writeString(iUserName);
				storage.getNetwork().writeString(iUserPassword);
			} finally {
				storage.endRequest();
			}

			try {
				storage.beginResponse();
				storage.txId = storage.getNetwork().readInt();
			} finally {
				storage.endResponse();
			}

		} catch (Exception e) {
			OLogManager.instance().error(this, "Can't create the remote storage: " + storage.getName(), e, OStorageException.class);
			storage.close();
		}

		return this;
	}

	public OServerAdmin createDatabase(String iStorageMode) throws IOException {
		storage.checkConnection();

		try {
			if (iStorageMode == null)
				iStorageMode = "csv";

			try {
				storage.beginRequest(OChannelBinaryProtocol.REQUEST_DB_CREATE);
				storage.getNetwork().writeString(storage.getName());
				storage.getNetwork().writeString(iStorageMode);
			} finally {
				storage.endRequest();
			}

			storage.getResponse();

		} catch (Exception e) {
			OLogManager.instance().error(this, "Can't create the remote storage: " + storage.getName(), e, OStorageException.class);
			storage.close();
		}
		return this;
	}

	public OServerAdmin deleteDatabase() throws IOException {
		storage.checkConnection();

		try {
			try {
				storage.beginRequest(OChannelBinaryProtocol.REQUEST_DB_DELETE);
				storage.getNetwork().writeString(storage.getName());
			} finally {
				storage.endRequest();
			}

			storage.getResponse();

		} catch (Exception e) {
			OLogManager.instance().exception("Can't delete the remote storage: " + storage.getName(), e, OStorageException.class);
			storage.close();
		}
		return this;
	}

	public OServerAdmin shareDatabase(final String iDatabaseName, final String iDatabaseUserName, final String iDatabaseUserPassword,
			final String iRemoteName, final boolean iSynchronousMode) throws IOException {

		try {
			try {
				storage.beginRequest(OChannelDistributedProtocol.REQUEST_DISTRIBUTED_DB_SHARE_SENDER);
				storage.getNetwork().writeString(iDatabaseName);
				storage.getNetwork().writeString(iDatabaseUserName);
				storage.getNetwork().writeString(iDatabaseUserPassword);
				storage.getNetwork().writeString(iRemoteName);
				storage.getNetwork().writeByte((byte) (iSynchronousMode ? 1 : 0));
			} finally {
				storage.endRequest();
			}

			storage.getResponse();

			OLogManager.instance().debug(this, "Database '%s' has been shared in mode '%s' with the server '%s'", iDatabaseName,
					iSynchronousMode, iRemoteName);

		} catch (Exception e) {
			OLogManager.instance().exception("Can't share the database: " + iDatabaseName, e, OStorageException.class);
		}

		return this;
	}

	public Map<String, String> getGlobalConfigurations() throws IOException {
		storage.checkConnection();

		final Map<String, String> config = new HashMap<String, String>();

		try {
			try {
				storage.beginRequest(OChannelBinaryProtocol.REQUEST_CONFIG_LIST);
			} finally {
				storage.endRequest();
			}

			try {
				storage.beginResponse();
				final int num = storage.getNetwork().readShort();
				for (int i = 0; i < num; ++i)
					config.put(storage.getNetwork().readString(), storage.getNetwork().readString());
			} finally {
				storage.endResponse();
			}

		} catch (Exception e) {
			OLogManager.instance().exception("Can't retrieve the configuration list", e, OStorageException.class);
			storage.close();
		}
		return config;
	}

	public String getGlobalConfiguration(final OGlobalConfiguration iConfig) throws IOException {
		storage.checkConnection();

		try {
			storage.beginRequest(OChannelBinaryProtocol.REQUEST_CONFIG_GET);
			storage.getNetwork().writeString(iConfig.getKey());
			storage.beginResponse();

			return storage.getNetwork().readString();

		} catch (Exception e) {
			OLogManager.instance().exception("Can't retrieve the configuration value: " + iConfig.getKey(), e, OStorageException.class);
			storage.close();
		}
		return null;
	}

	public OServerAdmin setGlobalConfiguration(final OGlobalConfiguration iConfig, final Object iValue) throws IOException {
		storage.checkConnection();

		try {
			storage.beginRequest(OChannelBinaryProtocol.REQUEST_CONFIG_SET);
			storage.getNetwork().writeString(iConfig.getKey());
			storage.getNetwork().writeString(iValue != null ? iValue.toString() : "");
			storage.beginResponse();

		} catch (Exception e) {
			OLogManager.instance().exception("Can't set the configuration value: " + iConfig.getKey(), e, OStorageException.class);
			storage.close();
		}
		return this;
	}

	public void close() {
		storage.close();
	}
}

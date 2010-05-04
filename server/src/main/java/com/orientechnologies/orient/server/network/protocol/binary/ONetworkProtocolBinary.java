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
package com.orientechnologies.orient.server.network.protocol.binary;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.parser.OSystemVariableResolver;
import com.orientechnologies.orient.core.command.OCommandRequestAbstract;
import com.orientechnologies.orient.core.command.OCommandRequestInternal;
import com.orientechnologies.orient.core.command.OCommandResultListener;
import com.orientechnologies.orient.core.db.ODatabaseComplex;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.raw.ODatabaseRaw;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.db.record.ODatabaseRecordTx;
import com.orientechnologies.orient.core.engine.local.OEngineLocal;
import com.orientechnologies.orient.core.engine.memory.OEngineMemory;
import com.orientechnologies.orient.core.exception.OSecurityAccessException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.security.OSecurity;
import com.orientechnologies.orient.core.metadata.security.OUser;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordFactory;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.ORecordSchemaAware;
import com.orientechnologies.orient.core.serialization.serializer.record.OSerializationThreadLocal;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializerAnyRuntime;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializerAnyStreamable;
import com.orientechnologies.orient.core.storage.OCluster;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;
import com.orientechnologies.orient.core.storage.impl.logical.OClusterLogical;
import com.orientechnologies.orient.core.storage.impl.memory.OStorageMemory;
import com.orientechnologies.orient.enterprise.channel.OChannel;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinary;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryProtocol;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryServer;
import com.orientechnologies.orient.server.OClientConnection;
import com.orientechnologies.orient.server.OClientConnectionManager;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.OServerMain;
import com.orientechnologies.orient.server.network.protocol.ONetworkProtocol;
import com.orientechnologies.orient.server.tx.OTransactionOptimisticProxy;

public class ONetworkProtocolBinary extends ONetworkProtocol {
	protected OClientConnection	connection;
	protected OChannelBinary		channel;
	protected OUser							account;

	private String							user;
	private String							passwd;
	private ODatabaseRaw				underlyingDatabase;

	private int									commandType;

	private int									lastCommandType						= -1;
	private String							lastCommandDetail					= null;
	private long								totalCommandExecutionTime	= 0;
	private long								lastCommandExecutionTime	= 0;

	public ONetworkProtocolBinary() {
		super(OServer.getThreadGroup(), "net-protocol-binary");
	}

	public void config(final Socket iSocket, final OClientConnection iConnection) throws IOException {
		channel = (OChannelBinary) new OChannelBinaryServer(iSocket);
		connection = iConnection;

		start();
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void execute() throws Exception {
		commandType = -1;

		long clock = 0;

		try {
			commandType = channel.readByte();

			clock = System.currentTimeMillis();

			switch (commandType) {

			case OChannelBinaryProtocol.CONNECT: {
				user = channel.readString();
				passwd = channel.readString();
				sendOk();
				channel.writeString(connection.id);
				break;
			}

			case OChannelBinaryProtocol.DB_OPEN: {
				String dbURL = channel.readString();
				String dbName = dbURL.substring(dbURL.lastIndexOf(":") + 1);

				user = channel.readString();
				passwd = channel.readString();

				// SEARCH THE DB IN MEMORY FIRST
				connection.database = (ODatabaseDocumentTx) OServerMain.server().getMemoryDatabases().get(dbName);

				if (connection.database == null)
					// SEARCH THE DB IN LOCAL FS
					connection.database = new ODatabaseDocumentTx(OServerMain.server().getStoragePath(dbName));

				if (connection.database.isClosed())
					if (connection.database.getStorage() instanceof OStorageMemory)
						connection.database.create();
					else
						connection.database.open(user, passwd);

				underlyingDatabase = ((ODatabaseRaw) ((ODatabaseComplex<?>) connection.database.getUnderlying()).getUnderlying());

				if (!(underlyingDatabase.getStorage() instanceof OStorageMemory) && !loadUserFromSchema(user, passwd)) {
					sendError(new OSecurityAccessException("Access denied to database '" + connection.database.getName() + "' for user: "
							+ user));
				} else {
					sendOk();
					channel.writeString(connection.id);
					channel.writeInt(connection.database.getClusterNames().size());
					for (OCluster c : ((OStorage) connection.database.getStorage()).getClusters()) {
						channel.writeString(c.getName());
						channel.writeInt(c.getId());
					}
				}
				break;
			}

			case OChannelBinaryProtocol.DB_CREATE: {
				String dbName = channel.readString();
				String storageMode = channel.readString();

				final String path;
				final String realPath;

				if (storageMode.equals(OEngineLocal.NAME)) {
					if (OServerMain.server().existsStoragePath(dbName))
						throw new IllegalArgumentException("Database '" + dbName + "' already exists.");

					path = storageMode + ":${ORIENT_HOME}/databases/" + dbName + "/" + dbName;
					realPath = OSystemVariableResolver.resolveSystemVariables(path);
				} else if (storageMode.equals(OEngineMemory.NAME)) {
					if (OServerMain.server().getMemoryDatabases().containsKey(dbName))
						throw new IllegalArgumentException("Database '" + dbName + "' already exists.");

					path = storageMode + ":" + dbName;
					realPath = path;
				} else
					throw new IllegalArgumentException("Can't create databse: storage mode '" + storageMode + "' is not supported.");

				connection.database = new ODatabaseDocumentTx(realPath);
				connection.database.create();

				if (storageMode.equals(OEngineLocal.NAME)) {
					// CLOSE IT BECAUSE IT WILL BE OPEN AT FIRST USE
					connection.database.close();

				} else if (storageMode.equals(OEngineMemory.NAME)) {
					// SAVE THE DB IN MEMORY
					OServerMain.server().getMemoryDatabases().put(dbName, connection.database);
				}

				underlyingDatabase = ((ODatabaseRaw) ((ODatabaseComplex<?>) connection.database.getUnderlying()).getUnderlying());

				sendOk();
				break;
			}

			case OChannelBinaryProtocol.DB_CLOSE:
				// connection.storage.close();
				break;

			case OChannelBinaryProtocol.DB_EXIST:
				channel.writeByte((byte) (connection.database.exists() ? 1 : 0));

				sendOk();
				break;

			case OChannelBinaryProtocol.CLUSTER_COUNT:
				int[] ids = new int[channel.readShort()];
				for (int i = 0; i < ids.length; ++i)
					ids[i] = channel.readShort();

				long count = connection.database.countClusterElements(ids);

				sendOk();
				channel.writeLong(count);
				break;

			case OChannelBinaryProtocol.CLUSTER_PHYSICAL_ADD: {
				final int num = connection.database.addPhysicalCluster(channel.readString(), channel.readString(), channel.readInt());

				sendOk();
				channel.writeShort((short) num);
				break;
			}

			case OChannelBinaryProtocol.CLUSTER_LOGICAL_ADD: {
				final int num = connection.database.addLogicalCluster(channel.readString(), connection.database.getDefaultClusterId());

				sendOk();
				OClusterLogical cluster = (OClusterLogical) connection.database.getStorage().getClusterById(num);
				channel.writeShort((short) num);
				channel.writeString(cluster.getRID().toString());
				break;
			}

			case OChannelBinaryProtocol.RECORD_LOAD:
				final ORawBuffer record = underlyingDatabase.read(channel.readShort(), channel.readLong());
				sendOk();
				if (record != null) {
					channel.writeByte((byte) 1);
					channel.writeBytes(record.buffer);
					channel.writeInt(record.version);
					channel.writeByte(record.recordType);
				} else
					channel.writeByte((byte) 0);
				break;

			case OChannelBinaryProtocol.RECORD_CREATE:
				final long location = underlyingDatabase.save(channel.readShort(), ORID.CLUSTER_POS_INVALID, channel.readBytes(), -1,
						channel.readByte());
				sendOk();
				channel.writeLong(location);
				break;

			case OChannelBinaryProtocol.RECORD_UPDATE:
				final int clusterId = channel.readShort();
				final long position = channel.readLong();

				long newVersion = underlyingDatabase.save(clusterId, position, channel.readBytes(), channel.readInt(), channel.readByte());

				// TODO: Handle it by using triggers
				if (clusterId == connection.database.getMetadata().getSchemaClusterId())
					if (position == OSchema.CLASSES_RECORD_NUM)
						connection.database.getMetadata().loadSchema();
					else if (position == OSecurity.SECURITY_RECORD_NUM)
						connection.database.getMetadata().loadSecurity();

				sendOk();

				channel.writeInt((int) newVersion);
				break;

			case OChannelBinaryProtocol.RECORD_DELETE:
				underlyingDatabase.delete(channel.readShort(), channel.readLong(), channel.readInt());
				sendOk();
				break;

			case OChannelBinaryProtocol.COUNT: {
				final String clusterName = channel.readString();
				final long size = connection.database.countClusterElements(clusterName);

				sendOk();

				channel.writeLong(size);
				break;
			}

			case OChannelBinaryProtocol.COMMAND: {
				final boolean asynch = channel.readByte() == 'a';

				final OCommandRequestAbstract command = (OCommandRequestAbstract) OStreamSerializerAnyStreamable.INSTANCE
						.fromStream(channel.readBytes());

				if (asynch) {
					// ASYNCHRONOUS
					final StringBuilder empty = new StringBuilder();

					command.setResultListener(new OCommandResultListener() {
						private int	items	= 0;

						public boolean result(final Object iRecord) {
							if (items == 0)
								try {
									sendOk();
									empty.append("-");
								} catch (IOException e1) {
								}

							if (command.getLimit() > -1 && items > command.getLimit())
								return false;

							try {
								channel.writeByte((byte) 1);
								items++;
								writeRecord((ORecordInternal<?>) iRecord);
								channel.flush();
							} catch (IOException e) {
								return false;
							}

							return true;
						}
					});

					((OCommandRequestInternal<ODatabaseRecord<?>>) connection.database.command(command)).execute();

					if (empty.length() == 0)
						try {
							sendOk();
						} catch (IOException e1) {
						}

					channel.writeByte((byte) 0);
				} else {
					// SYNCHRONOUS
					final Object result = ((OCommandRequestInternal<ODatabaseRecord<?>>) connection.database.command(command)).execute();

					sendOk();

					if (result == null) {
						// NULL VALUE
						channel.writeByte((byte) 'n');
						channel.writeBytes(null);
					} else if (result instanceof ORecord<?>) {
						// RECORD
						channel.writeByte((byte) 'r');
						writeRecord((ORecordInternal<?>) result);
					} else {
						// ANY OTHER (INCLUDING LITERALS)
						channel.writeByte((byte) 'a');
						channel.writeBytes(OStreamSerializerAnyRuntime.INSTANCE.toStream(result));
					}
				}
				break;
			}

			case OChannelBinaryProtocol.DICTIONARY_LOOKUP: {
				String key = channel.readString();

				ORecordInternal<?> value = connection.database.getDictionary().get(key);

				sendOk();

				writeRecord(value);
				break;
			}

			case OChannelBinaryProtocol.DICTIONARY_PUT: {
				String key = channel.readString();
				ORecordInternal<?> value = ORecordFactory.getRecord(channel.readByte());

				final ORecordId rid = new ORecordId(channel.readString());
				value.setIdentity(rid.clusterId, rid.clusterPosition);
				value.setDatabase(connection.database);

				value = connection.database.getDictionary().putRecord(key, value);

				sendOk();

				writeRecord(value);
				break;
			}

			case OChannelBinaryProtocol.DICTIONARY_REMOVE: {
				String key = channel.readString();
				ORecordInternal<?> value = connection.database.getDictionary().remove(key);

				sendOk();

				writeRecord(value);
				break;
			}

			case OChannelBinaryProtocol.DICTIONARY_SIZE: {
				sendOk();
				channel.writeInt(connection.database.getDictionary().size());
				break;
			}

			case OChannelBinaryProtocol.DICTIONARY_KEYS: {
				sendOk();
				channel.writeCollectionString(connection.database.getDictionary().keySet());
				break;
			}

			case OChannelBinaryProtocol.TX_COMMIT:
				((OStorageLocal) connection.database.getStorage()).commit(connection.database.getId(), new OTransactionOptimisticProxy(
						(ODatabaseRecordTx) connection.database.getUnderlying(), channel));

				sendOk();
				break;

			default:
				OLogManager.instance().error(this, "Request not supported. Code: " + commandType);

				channel.clearInput();
				sendError(null);
			}
		} catch (EOFException eof) {
			shutdown();
		} catch (SocketException e) {
			shutdown();
		} catch (OException e) {
			sendError(e);
		} catch (Throwable t) {
			OLogManager.instance().error(this, "Error on executing request", t);
			sendError(t);
		} finally {
			try {
				channel.flush();
			} catch (Throwable t) {
				OLogManager.instance().debug(this, "Error on send data over the network", t);
			}

			OSerializationThreadLocal.INSTANCE.get().clear();

			lastCommandExecutionTime = System.currentTimeMillis() - clock;
			totalCommandExecutionTime += lastCommandExecutionTime;

			lastCommandType = commandType;
		}
	}

	@Override
	public void shutdown() {
		sendShutdown();
		channel.close();

		OClientConnectionManager.instance().onClientDisconnection(connection.id);
	}

	protected void sendOk() throws IOException {
		channel.writeByte(OChannelBinaryProtocol.OK);
	}

	protected void sendError(Throwable t) throws IOException {
		channel.writeByte(OChannelBinaryProtocol.ERROR);
		channel.writeString(t.getClass().getName());
		channel.writeString(t != null ? t.getMessage() : null);

		channel.clearInput();
	}

	private boolean loadUserFromSchema(String iUserName, String iUserPassword) {
		account = connection.database.getMetadata().getSecurity().getUser(iUserName);
		if (account == null)
			throw new OSecurityAccessException("User '" + iUserName + "' was not found in database: " + connection.database.getName());

		boolean allow = account.checkPassword(iUserPassword);

		if (!allow)
			account = null;

		return allow;
	}

	/**
	 * Write a record using this format:<br/>
	 * - 2 bytes: class id [-2=no record, -1=no class id, > -1 = valid] <br/>
	 * - 1 byte: record type [v,c,b] <br/>
	 * - 2 bytes: cluster id <br/>
	 * - 8 bytes: position in cluster <br/>
	 * - 4 bytes: record version <br/>
	 * - x bytes: record vontent <br/>
	 * 
	 * @param iRecord
	 * @throws IOException
	 */
	private void writeRecord(ORecordInternal<?> iRecord) throws IOException {
		if (iRecord == null) {
			channel.writeShort((short) OChannelBinaryProtocol.RECORD_NULL);
		} else {
			channel.writeShort((short) (iRecord instanceof ORecordSchemaAware<?>
					&& ((ORecordSchemaAware<?>) iRecord).getSchemaClass() != null ? ((ORecordSchemaAware<?>) iRecord).getSchemaClass()
					.getId() : -1));

			channel.writeByte(iRecord.getRecordType());
			channel.writeShort((short) iRecord.getIdentity().getClusterId());
			channel.writeLong(iRecord.getIdentity().getClusterPosition());
			channel.writeInt(iRecord.getVersion());
			channel.writeBytes(iRecord.toStream());
		}
	}

	@Override
	public OChannel getChannel() {
		return channel;
	}

	public int getCommandType() {
		return commandType;
	}

	public int getLastCommandType() {
		return lastCommandType;
	}

	public String getLastCommandDetail() {
		return lastCommandDetail;
	}

	public long getTotalWorkingTime() {
		return totalCommandExecutionTime;
	}

	public long getLastCommandExecutionTime() {
		return lastCommandExecutionTime;
	}
}

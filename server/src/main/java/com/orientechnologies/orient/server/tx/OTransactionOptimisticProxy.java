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
package com.orientechnologies.orient.server.tx;

import java.io.IOException;
import java.util.Iterator;

import com.orientechnologies.orient.core.db.record.ODatabaseRecordTx;
import com.orientechnologies.orient.core.exception.OSerializationException;
import com.orientechnologies.orient.core.exception.OTransactionException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.tx.OTransactionAbstract;
import com.orientechnologies.orient.core.tx.OTransactionEntry;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinary;

@SuppressWarnings("unchecked")
public class OTransactionOptimisticProxy extends OTransactionAbstract<OTransactionRecordProxy> {
	private OTransactionEntryProxy	entry					= new OTransactionEntryProxy();
	private int											size;
	private OChannelBinary					channel;
	private boolean									emptyContent	= false;

	public OTransactionOptimisticProxy(final ODatabaseRecordTx iDatabase, final OChannelBinary iChannel) throws IOException {
		super(iDatabase, -1);

		channel = iChannel;
		id = iChannel.readInt();
		size = iChannel.readInt();
	}

	public Iterable<? extends OTransactionEntry<OTransactionRecordProxy>> getEntries() {

		return new Iterable<OTransactionEntry<OTransactionRecordProxy>>() {

			public Iterator<OTransactionEntry<OTransactionRecordProxy>> iterator() {
				return new Iterator<OTransactionEntry<OTransactionRecordProxy>>() {
					private int	current	= 1;

					public boolean hasNext() {
						if (emptyContent)
							return false;

						if (current > size)
							// AVOID BROWSING OF RECORDS THE SECOND TIME TO BE INSERTED IN SERVER-SIDE CACHE
							emptyContent = true;

						return current <= size;
					}

					public OTransactionEntry<OTransactionRecordProxy> next() {
						ORecordId rid = (ORecordId) entry.record.getIdentity();

						try {
							entry.status = channel.readByte();
							rid.clusterId = channel.readShort();

							switch (entry.status) {
							case OTransactionEntry.CREATED:
								entry.clusterName = channel.readString();
								entry.record.stream = channel.readBytes();
								break;

							case OTransactionEntry.UPDATED:
								rid.clusterPosition = channel.readLong();
								entry.record.version = channel.readInt();
								entry.record.stream = channel.readBytes();
								break;

							case OTransactionEntry.DELETED:
								rid.clusterPosition = channel.readLong();
								entry.record.version = channel.readInt();
								break;

							default:
								throw new OTransactionException("Unrecognized tx command: " + entry.status);
							}

							current++;

							return entry;
						} catch (IOException e) {
							throw new OSerializationException("Can't read transaction record from the network", e);
						}
					}

					public void remove() {
						throw new UnsupportedOperationException("remove");
					}
				};
			}
		};

	}

	public int size() {
		return size;
	}

	public void begin() {
		throw new UnsupportedOperationException("createRecord");
	}

	public void commit() {
		throw new UnsupportedOperationException("createRecord");
	}

	public void rollback() {
		throw new UnsupportedOperationException("createRecord");
	}

	public long createRecord(final OTransactionRecordProxy iContent) {
		throw new UnsupportedOperationException("createRecord");
	}

	public void delete(final OTransactionRecordProxy iRecord) {
		throw new UnsupportedOperationException("createRecord");
	}

	public OTransactionRecordProxy load(final int iClusterId, final long iPosition, final OTransactionRecordProxy iRecord) {
		throw new UnsupportedOperationException("createRecord");
	}

	public void save(final OTransactionRecordProxy iContent, final String iClusterName) {
		throw new UnsupportedOperationException("createRecord");
	}
}
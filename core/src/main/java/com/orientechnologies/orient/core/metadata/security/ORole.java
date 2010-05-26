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
package com.orientechnologies.orient.core.metadata.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.metadata.schema.OMetadataRecord;
import com.orientechnologies.orient.core.record.impl.ODocument;

/**
 * Contains the user settings about security and permissions roles.<br/>
 * Allowed operation are the classic CRUD, namely:
 * <ul>
 * <li>CREATE</li>
 * <li>READ</li>
 * <li>UPDATE</li>
 * <li>DELETE</li>
 * </ul>
 * Mode = ALLOW (allow all but) or DENY (deny all but)
 */
public class ORole extends OMetadataRecord {
	public enum ALLOW_MODES {
		DENY_ALL_BUT, ALLOW_ALL_BUT
	}

	// CRUD OPERATIONS
	public final static int			PERMISSION_NONE					= 0;
	public final static int			PERMISSION_CREATE				= 1;
	public final static int			PERMISSION_READ					= 2;
	public final static int			PERMISSION_UPDATE				= 4;
	public final static int			PERMISSION_DELETE				= 8;
	public final static int			PERMISSION_ALL					= PERMISSION_CREATE + PERMISSION_READ + PERMISSION_UPDATE + PERMISSION_DELETE;

	public final static String	PERMISSION_CREATE_TEXT	= "Create";
	public final static String	PERMISSION_READ_TEXT		= "Read";
	public final static String	PERMISSION_UPDATE_TEXT	= "Update";
	public final static String	PERMISSION_DELETE_TEXT	= "Delete";

	protected final static byte	STREAM_DENY							= 0;
	protected final static byte	STREAM_ALLOW						= 1;

	protected String						name;
	protected ALLOW_MODES				mode										= ALLOW_MODES.DENY_ALL_BUT;
	protected ORole							parentRole;
	protected Map<String, Byte>	rules										= new LinkedHashMap<String, Byte>();

	/**
	 * Constructor used in unmarshalling.
	 */
	public ORole(final ODatabaseRecord<?> iDatabase) {
		super(iDatabase, "ORole");
	}

	public ORole(final ODatabaseRecord<?> iDatabase, final String iName, final ORole iParent, final ALLOW_MODES iAllowMode) {
		this(iDatabase);
		name = iName;
		parentRole = iParent;
		mode = iAllowMode;
	}

	public boolean allow(final String iResource, final int iCRUDOperation) {
		// CHECK FOR SECURITY AS DIRECT RESOURCE
		Byte access = rules.get(iResource);
		if (access != null) {
			byte mask = (byte) iCRUDOperation;

			return (access.byteValue() & mask) == mask;
		}

		return mode == ALLOW_MODES.ALLOW_ALL_BUT;
	}

	public boolean hasRule(final String iResource) {
		return rules.containsKey(iResource);
	}

	public void addRule(final String iResource, final int iOperation) {
		rules.put(iResource, (byte) iOperation);
	}

	/**
	 * Grant a permission to the resource.
	 * 
	 * @param iResource
	 *          Requested resource
	 * @param iOperation
	 *          Permission to grant/add
	 */
	public void grant(final String iResource, final int iOperation) {
		final Byte current = rules.get(iResource);
		byte currentValue = current == null ? PERMISSION_NONE : current.byteValue();

		currentValue |= (byte) iOperation;

		rules.put(iResource, currentValue);
	}

	/**
	 * Revoke a permission to the resource.
	 * 
	 * @param iResource
	 *          Requested resource
	 * @param iOperation
	 *          Permission to grant/remove
	 */
	public void revoke(final String iResource, final int iOperation) {
		if (iOperation == PERMISSION_NONE)
			return;

		final Byte current = rules.get(iResource);

		byte currentValue;
		if (current == null)
			currentValue = PERMISSION_NONE;
		else {
			currentValue = current.byteValue();
			currentValue &= ~(byte) iOperation;
		}

		rules.put(iResource, currentValue);
	}

	public String getName() {
		return this.name;
	}

	public ALLOW_MODES getMode() {
		return mode;
	}

	public ORole setMode(final ALLOW_MODES iMode) {
		this.mode = iMode;
		return this;
	}

	public ORole getParentRole() {
		return parentRole;
	}

	public ORole setParentRole(final ORole iParent) {
		this.parentRole = iParent;
		return this;
	}

	public ORole fromDocument(final ODocument iSource) {
		recordId.copyFrom(iSource.getIdentity());

		name = iSource.field("name");
		mode = ((Integer) iSource.field("mode")) == STREAM_ALLOW ? ALLOW_MODES.ALLOW_ALL_BUT : ALLOW_MODES.DENY_ALL_BUT;

		parentRole = database.getMetadata().getSecurity().getRole((String) iSource.field("inheritedRole"));

		final Map<String, Integer> storedRules = iSource.field("rules");
		for (Entry<String, Integer> a : storedRules.entrySet()) {
			rules.put(a.getKey(), (byte) a.getValue().intValue());
		}
		return this;
	}

	/**
	 * Convert the permission code to a readable string.
	 * 
	 * @param iPermission
	 *          Permission to convert
	 * @return String representation of the permission
	 */
	public static String permissionToString(final int iPermission) {
		switch (iPermission) {
		case PERMISSION_CREATE:
			return PERMISSION_CREATE_TEXT;
		case PERMISSION_READ:
			return PERMISSION_READ_TEXT;
		case PERMISSION_UPDATE:
			return PERMISSION_UPDATE_TEXT;
		case PERMISSION_DELETE:
			return PERMISSION_DELETE_TEXT;
		}
		return "Unknown permission: " + iPermission;
	}

	@Override
	public byte[] toStream() {
		field("name", name);
		field("mode", mode == ALLOW_MODES.ALLOW_ALL_BUT ? STREAM_ALLOW : STREAM_DENY);
		field("inheritedRole", parentRole != null ? parentRole.name : null);
		field("rules", rules);
		return super.toStream();
	}

	public Set<Entry<String, Byte>> getRules() {
		return rules.entrySet();
	}

	@Override
	public String toString() {
		return name;
	}
}

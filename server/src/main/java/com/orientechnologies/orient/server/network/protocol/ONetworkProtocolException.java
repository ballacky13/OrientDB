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
package com.orientechnologies.orient.server.network.protocol;

import com.orientechnologies.common.exception.OException;

public class ONetworkProtocolException extends OException {

	private static final long	serialVersionUID	= -2655748565531836968L;

	public ONetworkProtocolException(String string) {
		super(string);
	}

	public ONetworkProtocolException(String message, Throwable cause) {
		super(message, cause);
	}

}
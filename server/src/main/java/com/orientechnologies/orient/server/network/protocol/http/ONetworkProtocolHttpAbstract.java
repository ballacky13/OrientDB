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
package com.orientechnologies.orient.server.network.protocol.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Date;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.profiler.OProfiler;
import com.orientechnologies.orient.core.OConstants;
import com.orientechnologies.orient.core.metadata.security.OUser;
import com.orientechnologies.orient.enterprise.channel.OChannel;
import com.orientechnologies.orient.enterprise.channel.text.OChannelTextServer;
import com.orientechnologies.orient.server.OClientConnection;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.config.OServerConfiguration;
import com.orientechnologies.orient.server.network.protocol.ONetworkProtocolDatabaseShared;
import com.orientechnologies.orient.server.network.protocol.ONetworkProtocolException;

public abstract class ONetworkProtocolHttpAbstract extends ONetworkProtocolDatabaseShared {
	private static final String			CONTENT_LENGTH			= "Content-Length: ";
	private static final byte[]			EOL									= { (byte) '\r', (byte) '\n' };
	private static final String			ORIENT_SERVER_KV		= "Orient Key Value v." + OConstants.ORIENT_VERSION;
	private static final int				MAX_CONTENT_LENGTH	= 10000;																							// MAX = 10Kb
	private static final int				TCP_DEFAULT_TIMEOUT	= 5000;

	protected OClientConnection			connection;
	protected OServerConfiguration	configuration;
	protected OChannelTextServer		channel;
	protected OUser									account;
	private String									httpVersion;

	public ONetworkProtocolHttpAbstract() {
		super(OServer.getThreadGroup(), "net-protocol-http");
	}

	public void config(Socket iSocket, OClientConnection iConnection) throws IOException {
		setName("HTTP-NetworkProtocol");

		iSocket.setSoTimeout(TCP_DEFAULT_TIMEOUT);
		channel = (OChannelTextServer) new OChannelTextServer(iSocket);
		connection = iConnection;
		configuration = new OServerConfiguration();

		start();
	}

	public void service(String iMethod, String iURI, String iRequest, OChannelTextServer iChannel) throws ONetworkProtocolException {
		OProfiler.getInstance().updateStatistic("OrientKV-Server.requests", +1);

		if (iMethod.equals("GET")) {
			doGet(iURI, iRequest, iChannel);
		} else if (iMethod.equals("PUT")) {
			doPut(iURI, iRequest, iChannel);
		} else if (iMethod.equals("POST")) {
			doPost(iURI, iRequest, iChannel);
		} else if (iMethod.equals("DELETE")) {
			doDelete(iURI, iRequest, iChannel);
		} else {
			OLogManager.instance().warn(
					this,
					"->" + iChannel.socket.getInetAddress().getHostAddress() + ": Error on HTTP method '" + iMethod
							+ "'. Valid methods are [GET," + "PUT,POST,DELETE]");
		}
	}

	public void doGet(String iURI, String iRequest, OChannelTextServer iChannel) throws ONetworkProtocolException {
	}

	public void doPut(String iURI, String iRequest, OChannelTextServer iChannel) throws ONetworkProtocolException {
	}

	public void doPost(String iURI, String iRequest, OChannelTextServer iChannel) throws ONetworkProtocolException {
	}

	public void doDelete(String iURI, String iRequest, OChannelTextServer iChannel) throws ONetworkProtocolException {
	}

	protected void sendTextContent(int iCode, String iReason, String iContentType, String iContent) throws IOException {
		sendStatus(iCode, iReason);
		sendResponseHeaders(iContentType);
		writeLine(CONTENT_LENGTH + (iContent != null ? iContent.length() + 1 : 0));
		writeLine(null);

		if (iContent != null && iContent.length() > 0) {
			writeLine(iContent);
		}

		channel.flush();
	}

	protected void sendBinaryContent(final int iCode, final String iReason, final String iContentType, final InputStream iContent,
			final long iSize) throws IOException {
		sendStatus(iCode, iReason);
		sendResponseHeaders(iContentType);
		writeLine(CONTENT_LENGTH + (iSize + 2));
		writeLine(null);

		int i = 0;
		while (iContent.available() > 0) {
			channel.outStream.write((byte) iContent.read());
			++i;
		}

		channel.flush();
	}

	protected void writeLine(final String iContent) throws IOException {
		if (iContent != null)
			channel.outStream.write(iContent.getBytes());
		channel.outStream.write(EOL);
	}

	protected void sendStatus(int iStatus, String iReason) throws IOException {
		writeLine(httpVersion + " " + iStatus + " " + iReason);
	}

	protected void sendResponseHeaders(String iContentType) throws IOException {
		writeLine("Cache-Control: no-cache, no-store, max-age=0, must-revalidate");
		writeLine("Pragma: no-cache");
		writeLine("Date: " + new Date());
		writeLine("Content-Type: " + iContentType);
		writeLine("Server: " + ORIENT_SERVER_KV);
		writeLine("Connection: Keep-Alive");
	}

	protected String readAllContent() throws IOException {
		StringBuilder request = new StringBuilder();
		char currChar;
		int contentLength = -1;
		boolean endOfHeaders = false;
		while (!channel.socket.isInputShutdown()) {
			currChar = (char) channel.inStream.read();

			if (currChar == '\r') {
				if (contentLength == -1) {
					String line = request.toString();
					if (line.startsWith(CONTENT_LENGTH)) {
						contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()));
						if (contentLength > MAX_CONTENT_LENGTH)
							OLogManager.instance().warn(
									this,
									"->" + channel.socket.getInetAddress().getHostAddress() + ": Error on content size " + contentLength
											+ ": the maximum allowed is " + MAX_CONTENT_LENGTH);
					}
				}

				// CONSUME /r or /n
				channel.inStream.read();

				if (!endOfHeaders && request.length() == 0) {
					if (contentLength <= 0)
						return null;

					// FIRST BLANK LINE: END OF HEADERS
					endOfHeaders = true;
				}

				request.setLength(0);

			} else if (endOfHeaders && request.length() == 0 && currChar != '\r' && currChar != '\n') {
				// END OF HEADERS
				byte[] buffer = new byte[contentLength];
				buffer[0] = (byte) currChar;
				channel.inStream.read(buffer, 1, contentLength - 1);
				return new String(buffer);
			} else
				request.append(currChar);
		}

		if (OLogManager.instance().isDebugEnabled())
			OLogManager.instance().debug(this,
					"Error on parsing HTTP content from client " + channel.socket.getInetAddress().getHostAddress() + ":\n" + request);

		return null;
	}

	@Override
	protected void execute() throws Exception {
		if (channel.socket.isInputShutdown()) {
			connectionClosed();
			return;
		}

		String method = null;
		String uri = null;

		long timer = -1;

		try {
			StringBuilder request = new StringBuilder();
			char c = (char) channel.inStream.read();

			if (channel.inStream.available() == 0) {
				// connectionClosed();
				return;
			}

			timer = OProfiler.getInstance().startChrono();

			request.setLength(0);

			if (c != '\n')
				// AVOID INITIAL /N
				request.append(c);

			while (!channel.socket.isInputShutdown()) {
				c = (char) channel.inStream.read();

				if (c == '\r') {
					String[] words = request.toString().split(" ");
					if (words.length < 3) {
						OLogManager.instance().warn(this,
								"->" + channel.socket.getInetAddress().getHostAddress() + ": Error on invalid content:\n" + request);
						break;
					}

					method = words[0];
					uri = words[1];
					httpVersion = words[2];

					// CONSUME THE NEXT \n
					channel.inStream.read();

					service(method, uri, readAllContent(), channel);

					return;
				}

				request.append(c);
			}

			if (OLogManager.instance().isDebugEnabled())
				OLogManager.instance().debug(this,
						"Parsing request from client " + channel.socket.getInetAddress().getHostAddress() + ":\n" + request);

		} catch (SocketException e) {
			connectionError();

		} catch (SocketTimeoutException e) {
			timeout();

		} catch (Throwable t) {
			if (method != null && uri != null)
				try {
					sendTextContent(505, "Error on excuting of " + method + " for the resource: " + uri, "text/plain", t.toString());
				} catch (IOException e) {
				}
		} finally {
			if (timer > -1)
				OProfiler.getInstance().stopChrono("ONetworkProtocolHttp.execute", timer);
		}
	}

	protected void connectionClosed() {
		OProfiler.getInstance().updateStatistic("OrientKV-Server.http.closed", +1);
		sendShutdown();
	}

	protected void timeout() {
		OProfiler.getInstance().updateStatistic("OrientKV-Server.http.timeout", +1);
		sendShutdown();
	}

	protected void connectionError() {
		OProfiler.getInstance().updateStatistic("OrientKV-Server.http.error", +1);
		sendShutdown();
	}

	@Override
	public void sendShutdown() {
		super.sendShutdown();

		try {
			channel.socket.close();
		} catch (IOException e) {
		}

		if (OLogManager.instance().isDebugEnabled())
			OLogManager.instance().debug(this, "Connection shutdowned");
	}

	@Override
	public OChannel getChannel() {
		return null;
	}
}
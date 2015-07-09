/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.box.webclient.client;

import static org.waveprotocol.wave.communication.gwt.JsonHelper.getPropertyAsInteger;
import static org.waveprotocol.wave.communication.gwt.JsonHelper.getPropertyAsObject;
import static org.waveprotocol.wave.communication.gwt.JsonHelper.getPropertyAsString;
import static org.waveprotocol.wave.communication.gwt.JsonHelper.setPropertyAsInteger;
import static org.waveprotocol.wave.communication.gwt.JsonHelper.setPropertyAsObject;
import static org.waveprotocol.wave.communication.gwt.JsonHelper.setPropertyAsString;

import com.google.common.base.Preconditions;
import com.google.gwt.user.client.Cookies;

import org.waveprotocol.box.common.comms.jso.ProtocolAuthenticateJsoImpl;
import org.waveprotocol.box.common.comms.jso.ProtocolOpenRequestJsoImpl;
import org.waveprotocol.box.common.comms.jso.ProtocolSubmitRequestJsoImpl;
import org.waveprotocol.box.common.comms.jso.ProtocolSubmitResponseJsoImpl;
import org.waveprotocol.box.common.comms.jso.ProtocolWaveletUpdateJsoImpl;
import org.waveprotocol.box.stat.Timer;
import org.waveprotocol.box.stat.Timing;
import org.waveprotocol.wave.client.events.ClientEvents;
import org.waveprotocol.wave.client.events.Log;
import org.waveprotocol.wave.client.events.NetworkStatusEvent;
import org.waveprotocol.wave.client.events.NetworkStatusEvent.ConnectionStatus;
import org.waveprotocol.wave.communication.gwt.JsonMessage;
import org.waveprotocol.wave.communication.json.JsonException;
import org.waveprotocol.wave.model.util.CollectionUtils;
import org.waveprotocol.wave.model.util.IntMap;

import java.util.Queue;


/**
 * Wrapper around WebSocket that handles the Wave client-server protocol.
 */
public class WaveWebSocketClient implements WaveSocket.WaveSocketCallback {
  private static final Log LOG = Log.get(WaveWebSocketClient.class);

  // Sets an specific session cookie name
  // private static final String JETTY_SESSION_TOKEN_NAME = "JSESSIONID";
  private static final String JETTY_SESSION_TOKEN_NAME = "WSESSIONID";

  /**
   * Envelope for delivering arbitrary messages. Each envelope has a sequence
   * number and a message. The format must match the format used in the server's
   * WebSocketChannel.
   * <p>
   * Note that this message can not be described by a protobuf, because it
   * contains an arbitrary protobuf, which breaks the protobuf typing rules.
   */
  private static final class MessageWrapper extends JsonMessage {
    static MessageWrapper create(int seqno, String type, JsonMessage message) {
      MessageWrapper wrapper = JsonMessage.createJsonMessage().cast();
      setPropertyAsInteger(wrapper, "sequenceNumber", seqno);
      setPropertyAsString(wrapper, "messageType", type);
      setPropertyAsObject(wrapper, "message", message);
      return wrapper;
    }

    @SuppressWarnings("unused") // GWT requires an explicit protected ctor
    protected MessageWrapper() {
      super();
    }

    int getSequenceNumber() {
      return getPropertyAsInteger(this, "sequenceNumber");
    }

    String getType() {
      return getPropertyAsString(this, "messageType");
    }

    <T extends JsonMessage> T getPayload() {
      return getPropertyAsObject(this, "message").<T>cast();
    }
  }

  private WaveSocket socket;
  private final IntMap<SubmitResponseCallback> submitRequestCallbacks;

  /**
   * Lifecycle of a socket is: (CONNECTING &#8594; CONNECTED &#8594;
   * DISCONNECTED)&#8727;
   */
  private enum ConnectState {
    CONNECTED, CONNECTING, DISCONNECTED
  }

  private ConnectState connected = ConnectState.DISCONNECTED;
  private WaveWebSocketCallback callback;
  private int sequenceNo;

  private final Queue<JsonMessage> messages = CollectionUtils.createQueue();

  private boolean connectedAtLeastOnce = false;
  private final String urlBase;

  public WaveWebSocketClient(boolean websocketNotAvailable, String urlBase) {
    this.urlBase = urlBase;
    submitRequestCallbacks = CollectionUtils.createIntMap();
    socket = WaveSocketFactory.create(websocketNotAvailable, urlBase, this);
  }

  /**
   * Attaches the handler for incoming messages. Once the client's workflow has
   * been fixed, this callback attachment will become part of
   * {@link #connect()}.
   */
  public void attachHandler(WaveWebSocketCallback callback) {
    Preconditions.checkState(this.callback == null);
    Preconditions.checkArgument(callback != null);
    this.callback = callback;
  }

  /**
   * Opens this connection.
   */
  public void connect() {
    connected = ConnectState.CONNECTING;
    socket.connect();
  }

  /**
   * Lets app to fully restart the connection.
   *
   */
  public void disconnect(boolean discardInFlightMessages) {
    connected = ConnectState.DISCONNECTED;
    socket.disconnect();
    connectedAtLeastOnce = false;
    if (discardInFlightMessages) messages.clear();
  }

  @Override
  public void onConnect() {
    connected = ConnectState.CONNECTED;


    // Sends the session cookie to the server via an RPC to work around browser bugs.
    // See: http://code.google.com/p/wave-protocol/issues/detail?id=119
    if (!connectedAtLeastOnce) {
      // Send the auth message if is the first connection
      String token = Cookies.getCookie(JETTY_SESSION_TOKEN_NAME);
      if (token != null) {
        ProtocolAuthenticateJsoImpl auth = ProtocolAuthenticateJsoImpl.create();
        auth.setToken(token);
        send(MessageWrapper.create(sequenceNo++, "ProtocolAuthenticate", auth));
      }
    }
    connectedAtLeastOnce = true;

    // Flush queued messages.
    while (!messages.isEmpty() && connected == ConnectState.CONNECTED) {
      send(messages.poll());
    }

    ClientEvents.get().fireEvent(new NetworkStatusEvent(ConnectionStatus.CONNECTED));
  }

  @Override
  public void onDisconnect() {
    connected = ConnectState.DISCONNECTED;
    ClientEvents.get().fireEvent(new NetworkStatusEvent(ConnectionStatus.DISCONNECTED));
  }

  @Override
  public void onDisconnect(String reason) {
    connected = ConnectState.DISCONNECTED;
    if (!reason.equals("200"))
      ClientEvents.get().fireEvent(new NetworkStatusEvent(ConnectionStatus.SERVER_ERROR));
    else
      ClientEvents.get().fireEvent(new NetworkStatusEvent(ConnectionStatus.DISCONNECTED));
  }

  @Override
  public void onMessage(final String message) {
    LOG.info("received JSON message " + message);
    Timer timer = Timing.start("deserialize message");
    MessageWrapper wrapper;
    try {
      wrapper = MessageWrapper.parse(message);
    } catch (JsonException e) {
      LOG.severe("invalid JSON message " + message, e);
      return;
    } finally {
      Timing.stop(timer);
    }
    String messageType = wrapper.getType();
    if ("ProtocolWaveletUpdate".equals(messageType)) {
      if (callback != null) {
        callback.onWaveletUpdate(wrapper.<ProtocolWaveletUpdateJsoImpl>getPayload());
      }
    } else if ("ProtocolSubmitResponse".equals(messageType)) {
      int seqno = wrapper.getSequenceNumber();
      SubmitResponseCallback callback = submitRequestCallbacks.get(seqno);
      if (callback != null) {
        submitRequestCallbacks.remove(seqno);
        callback.run(wrapper.<ProtocolSubmitResponseJsoImpl>getPayload());
      }
    }
  }

  public void submit(ProtocolSubmitRequestJsoImpl message, SubmitResponseCallback callback) {
    int submitId = sequenceNo++;
    submitRequestCallbacks.put(submitId, callback);
    send(MessageWrapper.create(submitId, "ProtocolSubmitRequest", message));
  }

  public void open(ProtocolOpenRequestJsoImpl message) {
    send(MessageWrapper.create(sequenceNo++, "ProtocolOpenRequest", message));
  }

  private void send(JsonMessage message) {
    switch (connected) {
      case CONNECTED:
        Timer timing = Timing.start("serialize message");
        String json;
        try {
          json = message.toJson();
        } finally {
          Timing.stop(timing);
        }
        LOG.info("Sending JSON data " + json);
        socket.sendMessage(json);
        break;
      default:
        messages.add(message);
    }
  }



}

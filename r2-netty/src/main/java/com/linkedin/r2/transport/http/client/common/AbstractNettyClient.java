package com.linkedin.r2.transport.http.client.common;

/*
   Copyright (c) 2017 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

import com.linkedin.common.callback.Callback;
import com.linkedin.common.util.None;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.message.Request;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.Response;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.message.stream.StreamRequest;
import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.transport.common.MessageType;
import com.linkedin.r2.transport.common.bridge.client.TransportClient;
import com.linkedin.r2.transport.common.bridge.common.TransportCallback;
import com.linkedin.r2.transport.common.bridge.common.TransportResponseImpl;
import com.linkedin.r2.transport.http.client.AbstractJmxManager;
import com.linkedin.r2.transport.http.client.AsyncPoolStats;
import com.linkedin.r2.transport.http.client.PoolStats;
import com.linkedin.r2.transport.http.client.TimeoutTransportCallback;
import com.linkedin.r2.transport.http.common.HttpBridge;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Abstract class implementation of {@link TransportClient} on top of Netty libraries. Inheriting this class is
 * a good starting point for protocol specific implementation of TransportClient.
 *
 * @author Steven Ihde
 * @author Ang Xu
 * @author Zhenkai Zhu
 * @author Francesco Capponi (fcapponi@linkedin.com)
 */

public abstract class AbstractNettyClient<Req extends Request, Res extends Response> implements TransportClient
{
  private static final Logger LOG = LoggerFactory.getLogger(AbstractNettyClient.class);
  private static final int HTTP_DEFAULT_PORT = 80;
  private static final int HTTPS_DEFAULT_PORT = 443;

  protected final ChannelPoolManager _channelPoolManager;
  protected final ChannelGroup _allChannels;

  protected final AtomicReference<State> _state = new AtomicReference<>(State.RUNNING);

  public enum State {RUNNING, SHUTTING_DOWN, REQUESTS_STOPPING, SHUTDOWN}

  protected final ScheduledExecutorService _scheduler;

  protected final long _requestTimeout;
  protected final long _shutdownTimeout;

  private final String _requestTimeoutMessage;
  private final AbstractJmxManager _jmxManager;

  /**
   * Creates a new HttpNettyClient
   * @param executor                  An executor; it is the caller's responsibility to shut it down
   * @param requestTimeout            Timeout, in ms, to get a connection from the pool or create one
   * @param shutdownTimeout           Timeout, in ms, the client should wait after shutdown is
   *                                  initiated before terminating outstanding requests
   * @param jmxManager                A management class that is aware of the creation/shutdown event
   *                                  of the underlying {@link ChannelPoolManager}
   * @param channelPoolManager        channelPoolManager instance to use in the factory
   */
  public AbstractNettyClient(ScheduledExecutorService executor,
                             long requestTimeout,
                             long shutdownTimeout,
                             AbstractJmxManager jmxManager,
                             ChannelPoolManager channelPoolManager)
  {
    _scheduler = executor;
    _requestTimeout = requestTimeout;
    _shutdownTimeout = shutdownTimeout;
    _requestTimeoutMessage = "Exceeded request timeout of " + _requestTimeout + "ms";
    _jmxManager = jmxManager;
    _channelPoolManager = channelPoolManager;
    _allChannels = _channelPoolManager.getAllChannels();
    _jmxManager.onProviderCreate(_channelPoolManager);
  }

  /* Constructor for test purpose ONLY. */
  public AbstractNettyClient(ChannelPoolFactory factory, ScheduledExecutorService executor, int requestTimeout,
      int shutdownTimeout) {
    _scheduler = executor;
    _requestTimeout = requestTimeout;
    _shutdownTimeout = shutdownTimeout;
    _requestTimeoutMessage = "Exceeded request timeout of " + _requestTimeout + "ms";
    _jmxManager = AbstractJmxManager.NULL_JMX_MANAGER;
    DefaultChannelGroup allChannels = new DefaultChannelGroup("R2 client channels", GlobalEventExecutor.INSTANCE);

    _channelPoolManager = new ChannelPoolManager(factory, allChannels);
    _jmxManager.onProviderCreate(_channelPoolManager);
    _allChannels = _channelPoolManager.getAllChannels();
  }

  /**
   * Given a callback, returns the wrapped callback that will be executed on a custom executor
   */
  protected abstract TransportCallback<Res> getExecutionCallback(TransportCallback<Res> callback);

  /**
   * Writes the given request to the given socket address and invokes the callback after request is sent.
   * @param request Request to send
   * @param context Request context
   * @param address Socket address to send the request to
   * @param wireAttrs attributes that should be sent over the wire to the server
   * @param callback Callback invoked after request is sent
   */
  protected abstract void doWriteRequest(final Req request, final RequestContext context, final SocketAddress address,
                                         Map<String, String> wireAttrs, final TimeoutTransportCallback<Res> callback);

  /**
   * Signals that the client has entered shutdown phase. Performs tasks necessary to shutdown the client and
   * invokes the callback after shutdown tasks are complete.
   *
   * @param callback callback invoked after shutdown is complete
   */
  protected abstract void doShutdown(final Callback<None> callback);

  @Override
  @SuppressWarnings("unchecked")
  public void restRequest(RestRequest request, RequestContext requestContext, Map<String, String> wireAttrs,
      final TransportCallback<RestResponse> callback) {
    MessageType.setMessageType(MessageType.Type.REST, wireAttrs);
    writeRequest((Req) request, requestContext, wireAttrs, (TransportCallback<Res>) HttpBridge.restToHttpCallback(callback, request));
  }

  @Override
  @SuppressWarnings("unchecked")
  public void streamRequest(StreamRequest request, RequestContext requestContext, Map<String, String> wireAttrs,
      TransportCallback<StreamResponse> callback) {
    MessageType.setMessageType(MessageType.Type.REST, wireAttrs);
    writeRequest((Req) request, requestContext, wireAttrs, (TransportCallback<Res>) HttpBridge.streamToHttpCallback(callback, request));
  }

  /**
   * This method calls the user defined method {@link AbstractNettyClient#doWriteRequest(Request, RequestContext, SocketAddress, Map, TimeoutTransportCallback)}
   * after having checked that the client is still running and resolved the DNS
   */
  private void writeRequest(Req request, RequestContext requestContext, Map<String, String> wireAttrs,
                            TransportCallback<Res> callback)
  {
    TransportCallback<Res> executionCallback = getExecutionCallback(callback);
    // By wrapping the callback in a Timeout callback before passing it along, we deny the rest
    // of the code access to the unwrapped callback.  This ensures two things:
    // 1. The user callback will always be invoked, since the Timeout will eventually expire
    // 2. The user callback is never invoked more than once
    TimeoutTransportCallback<Res> timeoutCallback =
      new TimeoutTransportCallback<>(_scheduler,
        _requestTimeout,
        TimeUnit.MILLISECONDS,
        executionCallback,
        _requestTimeoutMessage);

    // check lifecycle
    State state = _state.get();
    if (state != State.RUNNING)
    {
      errorResponse(callback, new IllegalStateException("Client is " + state));
      return;
    }

    // resolve address
    final SocketAddress address;
    try
    {
      address = resolveAddress(request, requestContext);
    }
    catch (UnknownHostException | UnknownSchemeException e)
    {
      errorResponse(callback, e);
      return;
    }

    doWriteRequest(request, requestContext, address, wireAttrs, timeoutCallback);
  }

  /**
   * Resolve the DNS of the request and decorate the requestContext with the resolved address
   */
  private SocketAddress resolveAddress(Req request, RequestContext requestContext) throws UnknownHostException, UnknownSchemeException
  {
    URI uri = request.getURI();
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new UnknownSchemeException("Unknown scheme: " + scheme + " (only http/https is supported)");
    }
    String host = uri.getHost();
    int port = uri.getPort();
    if (port == -1) {
      port = "http".equalsIgnoreCase(scheme) ? HTTP_DEFAULT_PORT : HTTPS_DEFAULT_PORT;
    }

    // TODO investigate DNS resolution and timing
    InetAddress inetAddress = InetAddress.getByName(host);
    final SocketAddress address = new InetSocketAddress(inetAddress, port);
    requestContext.putLocalAttr(R2Constants.REMOTE_SERVER_ADDR, inetAddress.getHostAddress());

    return address;
  }

  @Override
  public final void shutdown(final Callback<None> callback) {
    LOG.info("Shutdown requested");
    if (_state.compareAndSet(State.RUNNING, State.SHUTTING_DOWN)) {
      LOG.info("Shutting down");
      doShutdown(callback);
      _jmxManager.onProviderShutdown(_channelPoolManager);
    } else {
      callback.onError(new IllegalStateException("Shutdown has already been requested."));
    }
  }

  public static <T> void errorResponse(TransportCallback<T> callback, Throwable e) {
    callback.onResponse(TransportResponseImpl.error(e));
  }

  public static Exception toException(Throwable t) {
    if (t instanceof Exception) {
      return (Exception) t;
    }
    // This could probably be improved...
    return new Exception("Wrapped Throwable", t);
  }

  /**
   * Gets statistics from each channel pool. The map keys represent pool names.
   * The values are the corresponding {@link AsyncPoolStats} objects.
   *
   * @return A map of pool names and statistics.
   */
  public final Map<String, PoolStats> getPoolStats() {
    return _channelPoolManager.getPoolStats();
  }
}

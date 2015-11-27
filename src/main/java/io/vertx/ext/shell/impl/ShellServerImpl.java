/*
 * Copyright 2015 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 *
 *
 * Copyright (c) 2015 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *
 */

package io.vertx.ext.shell.impl;

import io.termd.core.tty.TtyConnection;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.shell.ShellServer;
import io.vertx.ext.shell.ShellServerOptions;
import io.vertx.ext.shell.command.CommandBuilder;
import io.vertx.ext.shell.command.CommandResolver;
import io.vertx.ext.shell.system.impl.InternalCommandManager;
import io.vertx.ext.shell.system.Shell;
import io.vertx.ext.shell.system.impl.ShellImpl;
import io.vertx.ext.shell.term.TermServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ShellServerImpl implements ShellServer {

  private final Vertx vertx;
  private final CopyOnWriteArrayList<CommandResolver> resolvers;
  private final InternalCommandManager manager;
  private final List<TermServer> termServers;
  private final long timeoutMillis;
  private final long reaperInterval;
  private String welcomeMessage;
  private boolean closed = true;
  private long timerID = -1;
  private final Map<String, ShellSession> sessions;
  private final Future<Void> sessionsClosed = Future.future();

  public ShellServerImpl(Vertx vertx, ShellServerOptions options) {
    this.vertx = vertx;
    this.welcomeMessage = options.getWelcomeMessage();
    this.termServers = new ArrayList<>();
    this.timeoutMillis = options.getSessionTimeout();
    this.sessions = new ConcurrentHashMap<>();
    this.reaperInterval = options.getReaperInterval();
    this.resolvers = new CopyOnWriteArrayList<>();
    this.manager = new InternalCommandManager(resolvers);

    // Register builtin commands so they are listed in help
    resolvers.add(() -> Arrays.asList(
        CommandBuilder.command("exit").processHandler(process -> {}).build(vertx),
        CommandBuilder.command("logout").processHandler(process -> {}).build(vertx),
        CommandBuilder.command("jobs").processHandler(process -> {}).build(vertx),
        CommandBuilder.command("fg").processHandler(process -> {}).build(vertx),
        CommandBuilder.command("bg").processHandler(process -> {}).build(vertx)
    ));
  }

  @Override
  public synchronized ShellServer registerCommandResolver(CommandResolver resolver) {
    resolvers.add(0, resolver);
    return this;
  }

  @Override
  public synchronized ShellServer registerTermServer(TermServer termServer) {
    termServers.add(termServer);
    return this;
  }

  private void handleConnection(TtyConnection conn) {
    synchronized (this) {
      // That might happen with multiple ser
      if (closed) {
        conn.close();
        return;
      }
    }
    ShellSession session = new ShellSession(vertx, conn, manager);
    session.setWelcome(welcomeMessage);
    session.closedFuture.setHandler(ar -> {
      boolean completeSessionClosed;
      synchronized (ShellServerImpl.this) {
        sessions.remove(session.id);
        completeSessionClosed = sessions.isEmpty() && closed;
      }
      if (completeSessionClosed) {
        sessionsClosed.complete();
      }
    });
    session.init();
    sessions.put(session.id, session); // Put after init so the close handler on the connection is set
    session.readLine(); // Now readline
  }

  @Override
  public ShellServer listen(Handler<AsyncResult<Void>> listenHandler) {
    List<TermServer> toStart;
    synchronized (this) {
      if (!closed) {
        throw new IllegalStateException("Server listening");
      }
      toStart = termServers;
    }
    AtomicInteger count = new AtomicInteger(toStart.size());
    if (count.get() == 0) {
      synchronized (this) {
        closed = false;
      }
      listenHandler.handle(Future.succeededFuture());
      return this;
    }
    AtomicBoolean failed = new AtomicBoolean();
    Handler<AsyncResult<TermServer>> handler = ar -> {
      if (ar.failed()) {
        failed.set(true);
      }
      if (count.decrementAndGet() == 0) {
        if (failed.get()) {
          listenHandler.handle(Future.failedFuture(ar.cause()));
          toStart.forEach(TermServer::close);
        } else {
          synchronized (this) {
            closed = false;
          }
          setTimer();
          listenHandler.handle(Future.succeededFuture());
        }
      }
    };
    toStart.forEach(termServer -> {
      termServer.connectionHandler(this::handleConnection);
      termServer.listen(handler);
    });
    return this;
  }

  private void evictSessions(long timerID) {
    long now = System.currentTimeMillis();
    Set<ShellSession> toClose = new HashSet<>();
    for (ShellSession session: sessions.values()) {
      if (now - session.lastAccessedTime() > timeoutMillis) {
        toClose.add(session);
      }
    }
    for (ShellSession session: toClose) {
      session.close();
    }
    setTimer();
  }

  private synchronized void setTimer() {
    if (!closed && reaperInterval > 0) {
      timerID = vertx.setTimer(reaperInterval, this::evictSessions);
    }
  }

  @Override
  public synchronized Shell createShell() {
    if (closed) {
      throw new IllegalStateException("Closed");
    }
    return new ShellImpl(manager);
  }

  @Override
  public void close(Handler<AsyncResult<Void>> completionHandler) {
    List<TermServer> toStop;
    List<ShellSession> toClose;
    synchronized (this) {
      if (closed) {
        toStop = Collections.emptyList();
        toClose = Collections.emptyList();
      } else {
        closed = true;
        if (timerID != -1) {
          vertx.cancelTimer(timerID);
        }
        toStop = termServers;
        toClose = new ArrayList<>(sessions.values());
        if (toClose.isEmpty()) {
          sessionsClosed.complete();
        }
      }
    }
    if (toStop.isEmpty() && toClose.isEmpty()) {
      completionHandler.handle(Future.succeededFuture());
    } else {
      AtomicInteger count = new AtomicInteger(1 + toClose.size());
      Handler<AsyncResult<Void>> handler = ar -> {
        if (count.decrementAndGet() == 0) {
          completionHandler.handle(Future.succeededFuture());
        }
      };
      toClose.forEach(ShellSession::close);
      toStop.forEach(termServer -> termServer.close(handler));
      sessionsClosed.setHandler(handler);
    }
  }
}
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

import io.vertx.core.*;
import io.vertx.ext.shell.ShellServer;
import io.vertx.ext.shell.ShellServerOptions;
import io.vertx.ext.shell.command.CommandRegistry;
import io.vertx.ext.shell.command.CommandResolver;
import io.vertx.ext.shell.spi.CommandResolverFactory;
import io.vertx.ext.shell.term.SSHTermOptions;
import io.vertx.ext.shell.ShellService;
import io.vertx.ext.shell.ShellServiceOptions;
import io.vertx.ext.shell.term.TelnetTermOptions;
import io.vertx.ext.shell.term.HttpTermOptions;
import io.vertx.ext.shell.term.impl.SSHServer;
import io.vertx.ext.shell.term.impl.TelnetTermServer;
import io.vertx.ext.shell.term.impl.HttpTermServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ShellServiceImpl implements ShellService {

  private final Vertx vertx;
  private final ShellServiceOptions options;
  private final ShellServer server;
  private final CommandRegistry registry;

  public ShellServiceImpl(Vertx vertx, ShellServiceOptions options) {
    this.vertx = vertx;
    this.options = options;
    this.server = ShellServer.create(vertx, new ShellServerOptions(options));
    this.registry = CommandRegistry.getShared(vertx);
  }

  @Override
  public ShellServer server() {
    return server;
  }

  @Override
  public Future<Void> start() {

    Promise<Void> p = Promise.promise();

    // Lookup providers
    ServiceLoader<CommandResolverFactory> loader = ServiceLoader.load(CommandResolverFactory.class);
    Iterator<CommandResolverFactory> it = loader.iterator();
    List<CommandResolverFactory> factories = new ArrayList<>();
    factories.add((vertx, handler) -> handler.handle(Future.succeededFuture(CommandResolver.baseCommands(vertx))));
    while (true) {
      try {
        if (it.hasNext()) {
          CommandResolverFactory factory = it.next();
          factories.add(factory);
        } else {
          break;
        }
      } catch (Exception e) {
      }
    }

    // When providers are registered we start the server
    AtomicInteger count = new AtomicInteger(factories.size());
    List<CommandResolver> resolvers = new ArrayList<>();
    resolvers.add(registry);
    Handler<Void> startServer = v -> {
      if (count.decrementAndGet() == 0) {
        startServer(resolvers, p);
      }
    };
    for (CommandResolverFactory factory : factories) {
      factory.resolver(vertx, ar -> {
        if (ar.succeeded()) {
          resolvers.add(ar.result());
        }
        startServer.handle(null);
      });
    }

    return p.future();
  }

  private void startServer(List<CommandResolver> resolvers, Handler<AsyncResult<Void>> startHandler) {
    TelnetTermOptions telnetOptions = options.getTelnetOptions();
    SSHTermOptions sshOptions = options.getSSHOptions();
    HttpTermOptions webOptions = options.getHttpOptions();
    if (telnetOptions != null) {
      server.registerTermServer(new TelnetTermServer(vertx, telnetOptions));
    }
    if (sshOptions != null) {
      server.registerTermServer(new SSHServer(vertx, sshOptions));
    }
    if (webOptions != null) {
      server.registerTermServer(new HttpTermServer(vertx, webOptions));
    }
    resolvers.forEach(server::registerCommandResolver);
    server.listen()
      .onComplete(startHandler);
  }

  @Override
  public Future<Void> stop() {
    return server.close();
  }
}

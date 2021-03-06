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

package io.vertx.ext.shell.cli;

import io.termd.core.util.Helper;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Vertx;
import io.vertx.ext.shell.session.Session;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The completion object
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@VertxGen
public interface Completion {

  @GenIgnore
  static String findLongestCommonPrefix(Collection<String> values) {
    return Helper.fromCodePoints(io.termd.core.readline.Completion.findLongestCommonPrefix(
        values.stream().map(Helper::toCodePoints).collect(Collectors.toList())
    ));
  }

  /**
   * @return the current Vert.x instance
   */
  Vertx vertx();

  /**
   * @return the shell current session, useful for accessing data like the current path for file completion, etc...
   */
  Session session();

  /**
   * @return the current line being completed in raw format, i.e without any char escape performed
   */
  String rawLine();

  /**
   * @return the current line being completed as preparsed tokens
   */
  List<CliToken> lineTokens();

  /**
   * End the completion with a list of candidates, these candidates will be displayed by the shell on the console.
   *
   * @param candidates the candidates
   */
  void complete(List<String> candidates);

  /**
   * End the completion with a value that will be inserted to complete the line.
   *
   * @param value the value to complete with
   * @param terminal true if the value is terminal, i.e can be further completed
   */
  void complete(String value, boolean terminal);

}

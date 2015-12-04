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

package io.vertx.ext.shell.system;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@DataObject(generateConverter = true)
public class ProcessStatus {

  private ExecStatus execStatus;
  private boolean foreground;
  private int exitCode;

  public ProcessStatus() {
  }

  public ProcessStatus(JsonObject json) {
    // Todo
  }

  public ProcessStatus(ProcessStatus that) {
    execStatus = that.execStatus;
    foreground = that.foreground;
    exitCode = that.exitCode;
  }

  public ExecStatus getExecStatus() {
    return execStatus;
  }

  public ProcessStatus setExecStatus(ExecStatus execStatus) {
    this.execStatus = execStatus;
    return this;
  }

  public boolean isForeground() {
    return foreground;
  }

  public ProcessStatus setForeground(boolean foreground) {
    this.foreground = foreground;
    return this;
  }

  public int getExitCode() {
    return exitCode;
  }

  public ProcessStatus setExitCode(int exitCode) {
    this.exitCode = exitCode;
    return this;
  }

  public JsonObject toJson() {
    return null;
  }
}

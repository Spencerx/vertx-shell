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

package io.vertx.ext.shell;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.ext.shell.cli.CliToken;
import io.vertx.ext.shell.command.CommandBuilder;
import io.vertx.ext.shell.term.Pty;
import io.vertx.ext.shell.registry.CommandRegistry;
import io.vertx.ext.shell.session.Session;
import io.vertx.ext.shell.system.Job;
import io.vertx.ext.shell.system.Shell;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@RunWith(VertxUnitRunner.class)
public class ShellServiceTest {

  Vertx vertx;
  CommandRegistry registry;
  ShellService service;

  @Before
  public void before() {
    vertx = Vertx.vertx();
    registry = CommandRegistry.get(vertx);
    service = ShellService.create(vertx);
  }

  @After
  public void after(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }

  @Test
  public void testRun(TestContext context) {
    CommandBuilder cmd = CommandBuilder.command("foo");
    cmd.processHandler(process -> {
      process.end(3);
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell session = service.createShell();
      Job job = session.createJob(CliToken.tokenize("foo"));
      Async async = context.async();
      job.setTty(Pty.create().slave()).terminateHandler(code -> {
        context.assertEquals(3, code);
        async.complete();
      }).run();
    }));
  }

  @Test
  public void testThrowExceptionInProcess(TestContext context) {
    CommandBuilder cmd = CommandBuilder.command("foo");
    cmd.processHandler(process -> {
      throw new RuntimeException();
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell shell = service.createShell();
      Job job = shell.createJob("foo");
      Async async = context.async();
      Pty pty = Pty.create();
      job.setTty(pty.slave()).terminateHandler(code -> {
        context.assertEquals(1, code);
        async.complete();
      }).run();
    }));
  }

  @Test
  public void testStdin(TestContext context) {
    CommandBuilder cmd = CommandBuilder.command("foo");
    CountDownLatch latch = new CountDownLatch(1);
    cmd.processHandler(process -> {
      process.setStdin(data -> {
        context.assertEquals("hello_world", data);
        process.end(0);
      });
      latch.countDown();
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell session = service.createShell();
      Job job = session.createJob(CliToken.tokenize("foo"));
      Async async = context.async();
      Pty pty = Pty.create();
      job.setTty(pty.slave());
      job.terminateHandler(code -> {
        context.assertEquals(0, code);
        async.complete();
      }).run();
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        context.fail(e);
      }
      pty.stdin().write("hello_world");
    }));
  }

  @Test
  public void testStdout(TestContext context) {
    CommandBuilder cmd = CommandBuilder.command("foo");
    cmd.processHandler(process -> {
      process.stdout().write("bye_world");
      process.end(0);
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell session = service.createShell();
      Job job = session.createJob("foo");
      Async async = context.async();
      LinkedList<String> out = new LinkedList<>();
      Pty pty = Pty.create();
      pty.setStdout(out::add);
      job.setTty(pty.slave());
      job.terminateHandler(code -> {
        context.assertEquals(0, code);
        context.assertEquals(Arrays.asList("bye_world"), out);
        async.complete();
      }).run();
    }));
  }

  @Test
  public void testVertxContext(TestContext testContext) throws Exception {
    Context commandCtx = vertx.getOrCreateContext();
    Context shellCtx = vertx.getOrCreateContext();
    Async async = testContext.async();
    CountDownLatch latch = new CountDownLatch(1);
    commandCtx.runOnContext(v1 -> {
      CommandBuilder cmd = CommandBuilder.command("foo");
      cmd.processHandler(process -> {
        Context currentCtx = Vertx.currentContext();
        testContext.assertTrue(commandCtx == currentCtx);
        process.setStdin(text -> {
          testContext.assertTrue(commandCtx == Vertx.currentContext());
          testContext.assertEquals("ping", text);
          process.write("pong");
        });
        process.suspendHandler(event -> {
          testContext.assertTrue(commandCtx == Vertx.currentContext());
          process.end(0);
        });
        latch.countDown();
      });
      registry.registerCommand(cmd.build(), testContext.asyncAssertSuccess(v2 -> {
        shellCtx.runOnContext(v3 -> {
          testContext.assertTrue(shellCtx == Vertx.currentContext());
          Shell shell = service.createShell();
          Job job = shell.createJob("foo");
          Pty pty = Pty.create();
          pty.setStdout(text -> {
            testContext.assertTrue(shellCtx == Vertx.currentContext());
            testContext.assertEquals("pong", text);
            job.suspend();
          });
          job.setTty(pty.slave()).terminateHandler(code -> {
            testContext.assertTrue(shellCtx == Vertx.currentContext());
            async.complete();
          }).run();
          try {
            latch.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            testContext.fail(e);
          }
          pty.stdin().write("ping");
        });
      }));
    });
  }

  @Test
  public void testSendEvent(TestContext context) {
    CommandBuilder cmd = CommandBuilder.command("foo");
    CountDownLatch latch = new CountDownLatch(1);
    cmd.processHandler(process -> {
      process.suspendHandler(v -> {
        process.end(0);
      });
      latch.countDown();
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell shell = service.createShell();
      Job job = shell.createJob("foo");
      Async async = context.async();
      Pty pty = Pty.create();
      job.setTty(pty.slave()).terminateHandler(status -> {
        async.complete();
      }).run();
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        context.fail(e);
      }
      job.suspend();
    }));
  }

  @Test
  public void testResize(TestContext context) {
    CommandBuilder cmd = CommandBuilder.command("foo");
    cmd.processHandler(process -> {
      context.assertEquals(20, process.width());
      context.assertEquals(10, process.height());
      process.resizehandler(v -> {
        context.assertEquals(25, process.width());
        context.assertEquals(15, process.height());
        process.end(0);
      });
      process.stdout().write("ping");
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell shell = service.createShell();
      Job job = shell.createJob("foo");
      Pty pty = Pty.create();
      Async async = context.async();
      pty.setSize(20, 10);
      pty.setStdout(text -> {
        pty.setSize(25, 15);
      });
      job.setTty(pty.slave()).terminateHandler(status -> {
        async.complete();
      }).run();
    }));
  }

  @Test
  public void testSessionGet(TestContext context) throws Exception {
    CommandBuilder cmd = CommandBuilder.command("foo");
    Async async = context.async();
    cmd.processHandler(process -> {
      Session session = process.session();
      context.assertNotNull(session);
      context.assertEquals("the_value", session.get("the_key"));
      process.end();
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell shell = service.createShell();
      Job job = shell.createJob("foo");
      shell.session().put("the_key", "the_value");
      Pty pty = Pty.create();
      job.setTty(pty.slave()).terminateHandler(status -> {
        context.assertEquals(0, status);
        async.complete();
      }).run();
    }));
  }

  @Test
  public void testSessionPut(TestContext context) throws Exception {
    CommandBuilder cmd = CommandBuilder.command("foo");
    Async async = context.async();
    cmd.processHandler(process -> {
      Session session = process.session();
      context.assertNotNull(session);
      context.assertNull(session.get("the_key"));
      session.put("the_key", "the_value");
      process.end();
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell shell = service.createShell();
      Job job = shell.createJob("foo");
      Pty pty = Pty.create();
      job.setTty(pty.slave()).terminateHandler(status -> {
        context.assertEquals(0, status);
        context.assertEquals("the_value", shell.session().get("the_key"));
        async.complete();
      }).run();
    }));
  }

  @Test
  public void testSessionRemove(TestContext context) throws Exception {
    CommandBuilder cmd = CommandBuilder.command("foo");
    Async async = context.async();
    cmd.processHandler(process -> {
      Session session = process.session();
      context.assertNotNull(session);
      context.assertEquals("the_value", session.remove("the_key"));
      process.end();
    });
    registry.registerCommand(cmd.build(), context.asyncAssertSuccess(v -> {
      Shell shell = service.createShell();
      Job job = shell.createJob("foo");
      Pty pty = Pty.create();
      shell.session().put("the_key", "the_value");
      job.setTty(pty.slave()).terminateHandler(status -> {
        context.assertEquals(0, status);
        context.assertNull(shell.session().get("the_key"));
        async.complete();
      }).run();
    }));
  }

  @Test
  public void testClose(TestContext context) {
    Async async = context.async(2);
    Async registrationLatch = context.async();
    Async runningLatch = context.async();
    CommandBuilder cmd = CommandBuilder.command("foo");
    cmd.processHandler(process -> {
      process.endHandler(v -> async.complete());
      runningLatch.complete();
    });
    registry.registerCommand(cmd.build(), ar -> registrationLatch.complete());
    registrationLatch.awaitSuccess(10000);
    Shell shell = service.createShell();
    Job job = shell.createJob("foo");
    Pty pty = Pty.create();
    job.setTty(pty.slave()).terminateHandler(status -> {
      async.complete();
    }).run();
    runningLatch.awaitSuccess(10000);
    shell.close();
    async.awaitSuccess(10000);
    context.assertEquals(0, shell.jobs().size());
  }

  // Just some proof of concept, there are issues because we there is a timing
  // problem when wiring processes
/*
  @Test
  public void testPipe(TestContext context) {
    CommandManager manager = CommandManager.create(vertx);
    Command cmd1 = Command.create("cmd1");
    cmd1.processHandler(process -> {
      process.setStdin(text -> {
        process.stdout().handle("|" + text + "|");
        process.end(0);
      });
    });
    Command cmd2 = Command.create("cmd2");
    cmd2.processHandler(process -> {
      System.out.println("");
      process.setStdin(text -> {
        process.stdout().handle("<" + text + ">");
        process.end(0);
      });
    });
    manager.registerCommand(cmd.build()1, context.asyncAssertSuccess(v1 -> {
      manager.registerCommand(cmd.build()2, context.asyncAssertSuccess(v2 -> {
        Shell shell = Shell.create(vertx, manager);
        shell.createJob("cmd2", context.asyncAssertSuccess(process2 -> {
          Async async = context.async();
          process2().setStdout(text -> {
            context.assertEquals("<|hello|>", text);
            async.complete();
          });
          process2.run(a1 -> {
            shell.createJob("cmd1", context.asyncAssertSuccess(process1 -> {
              process1().setStdout(process2().stdin());
              process1.run(a2 -> {
                process1().stdin().handle("hello");
              });
            }));
          });
        }));
      }));
    }));
  }
*/

}
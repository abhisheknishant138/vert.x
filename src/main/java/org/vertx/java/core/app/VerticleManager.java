package org.vertx.java.core.app;

import org.vertx.java.core.Handler;
import org.vertx.java.core.SimpleHandler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VertxInternal;
import org.vertx.java.core.app.groovy.GroovyVerticleFactory;
import org.vertx.java.core.app.java.JavaVerticleFactory;
import org.vertx.java.core.app.jruby.JRubyVerticleFactory;
import org.vertx.java.core.app.rhino.RhinoVerticleFactory;
import org.vertx.java.core.logging.Logger;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class VerticleManager {

  private static final Logger log = Logger.getLogger(VerticleManager.class);

  public static VerticleManager instance = new VerticleManager();

  private Map<String, AppMetaData> appMeta = new HashMap<>();
  private Map<String, List<VerticleHolder>> apps = new HashMap();
  private CountDownLatch stopLatch = new CountDownLatch(1);

  private VerticleManager() {
  }

  public void block() {
    while (true) {
      try {
        stopLatch.await();
        break;
      } catch (InterruptedException e) {
        //Ignore
      }
    }
  }

  public void unblock() {
    stopLatch.countDown();
  }

  public synchronized void deploy(boolean worker, VerticleType type, final String appName, final String main, final URL[] urls,
                                  int instances,
                                  final Handler<Void> doneHandler)
    throws Exception {

    log.debug("Deploying name : " + appName + " type: " + type + " main: " + main +
             " instances: " + instances);

    if (appMeta.containsKey(appName)) {
      throw new IllegalStateException("There is already a deployment with name: " + appName);
    }

    final VerticleFactory appFactory;
      switch (type) {
        case JAVA:
          appFactory = new JavaVerticleFactory();
          break;
        case RUBY:
          appFactory = new JRubyVerticleFactory();
          break;
        case JS:
          appFactory = new RhinoVerticleFactory();
          break;
        case GROOVY:
          appFactory = new GroovyVerticleFactory();
          break;
        default:
          throw new IllegalArgumentException("Unsupported type: " + type);
      }

    final int instCount = instances;

    class AggHandler {
      final AtomicInteger count = new AtomicInteger(0);

      void started() {
        if (count.incrementAndGet() == instCount) {
          log.debug("Started " + instCount + " instances ok");
          if (doneHandler != null) {
            doneHandler.handle(null);
          }
        }
      }
    }

    final AggHandler aggHandler = new AggHandler();

    for (int i = 0; i < instances; i++) {

      // Launch the app instance

      Runnable runner = new Runnable() {
        public void run() {

          Verticle app;
          try {
            app = appFactory.createVerticle(main, new ParentLastURLClassLoader(urls, getClass()
                .getClassLoader()));
          } catch (Throwable t) {
            log.error("Failed to create verticle", t);
            internalUndeploy(appName, doneHandler);
            return;
          }

          try {
            app.start();
            addApp(appName, app);
          } catch (Throwable t) {
            log.error("Unhandled exception in verticle start", t);
            internalUndeploy(appName, doneHandler);
          }
          aggHandler.started();
        }
      };

      if (worker) {
        VertxInternal.instance.startInBackground(runner);
      } else {
        VertxInternal.instance.startOnEventLoop(runner);
      }

    }
    appMeta.put(appName, new AppMetaData(urls, main));
  }

  private static class AggHandler extends SimpleHandler {
    AggHandler(int count, Handler<Void> doneHandler) {
      this.count = count;
      this.doneHandler = doneHandler;
    }
    int count;
    Handler<Void> doneHandler;
    public void handle() {
      if (--count == 0) {
        doneHandler.handle(null); // All undeployed
      }
    }
  }

  public synchronized void undeployAll(final Handler<Void> doneHandler) {
    if (appMeta.isEmpty()) {
      doneHandler.handle(null);
    } else {
      AggHandler aggHandler = new AggHandler(appMeta.size(), doneHandler);
      Set<String> names = new HashSet<>(appMeta.keySet()); // Avoid comod exception
      for (String name: names) {
        undeploy(name, aggHandler);
      }
    }
  }

  public synchronized String undeploy(String name, final Handler<Void> doneHandler) {
    if (appMeta.get(name) == null) {
      return "There is no deployment with name " + name;
    }
    internalUndeploy(name, doneHandler);
    return null;
  }

  private void internalUndeploy(String name, final Handler<Void> doneHandler) {
    List<VerticleHolder> list = apps.get(name);
    if (list != null) {
      log.debug("Undeploying " + list.size() + " instances in deployment: " + name);

      final AggHandler aggHandler = doneHandler == null ? null : new AggHandler(list.size(), doneHandler);

      for (final VerticleHolder holder: list) {
        VertxInternal.instance.executeOnContext(holder.contextID, new Runnable() {
          public void run() {
            VertxInternal.instance.setContextID(holder.contextID);
            try {
              holder.verticle.stop();
            } catch (Exception e) {
              log.error("Unhandled exception in verticle stop", e);
            }
            //FIXME - we need to destroy the context, but not until after the app has fully stopped which may
            //be asynchronous, e.g. if the app needs to close servers
            //VertxInternal.instance.destroyContext(holder.contextID);
            if (aggHandler != null) {
              aggHandler.handle(null);
            }
          }
        });
      }
    }
    appMeta.remove(name);
    apps.remove(name);
  }


  public synchronized Map<String, Integer> listInstances() {
    Map<String, Integer> map = new HashMap<>();
    for (Map.Entry<String, List<VerticleHolder>> entry: apps.entrySet()) {
      map.put(entry.getKey(), entry.getValue().size());
    }
    return map;
  }

  // Must be sychronized since called directly after app is deployed from different thread
  private synchronized void addApp(String name, Verticle app) {
    List<VerticleHolder> list = apps.get(name);
    if (list == null) {
      list = new ArrayList<>();
      apps.put(name, list);
    }
    list.add(new VerticleHolder(Vertx.instance.getContextID(), app));
  }

  private static class AppMetaData {
    final URL[] urls;
    final String main;

    private AppMetaData(URL[] urls, String mainClass) {
      this.urls = urls;
      this.main = mainClass;
    }
  }

  private static class VerticleHolder {
    final long contextID;
    final Verticle verticle;

    private VerticleHolder(long contextID, Verticle verticle) {
      this.contextID = contextID;
      this.verticle = verticle;
    }
  }
}

/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.speedtracer.client.model;

import com.google.gwt.chrome.crx.client.DevTools;
import com.google.gwt.chrome.crx.client.events.DevToolsPageEvent.Listener;
import com.google.gwt.chrome.crx.client.events.DevToolsPageEvent.PageEvent;
import com.google.gwt.chrome.crx.client.events.Event.ListenerHandle;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.speedtracer.client.model.EventVisitor.PostOrderVisitor;
import com.google.speedtracer.client.model.EventVisitor.PreOrderVisitor;
import com.google.speedtracer.client.model.ResourceUpdateEvent.UpdateResource;

/**
 * This class is used in Chrome when we are getting data from the devtools API.
 * Its job is to receive data from the devtools API, ensure that the data in
 * properly transformed into a consumable form, and to invoke callbacks passed
 * in from the UI. We use this overlay type as the object we pass to the Monitor
 * UI.
 */
public class DevToolsDataInstance extends DataInstance {
  /**
   * Proxy class that normalizes data coming in from the devtools API into a
   * digestable form, and then forwards it on to the DevToolsDataInstance.
   */
  public static class Proxy implements DataProxy {
    /**
     * Simple routing dispatcher used by the DevToolsDataProxy to quickly route.
     */
    static native Dispatcher createDispatcher(Proxy delegate) /*-{
      return {
        addRecordToTimeline: function(record) {
          delegate.@com.google.speedtracer.client.model.DevToolsDataInstance.Proxy::onTimeLineRecord(Lcom/google/speedtracer/client/model/EventRecord;)(record[1]);
        },
        updateResource: function(update) {
          delegate.@com.google.speedtracer.client.model.DevToolsDataInstance.Proxy::onUpdateResource(ILcom/google/gwt/core/client/JavaScriptObject;)(update[1], update[2]);
        }
      };
    }-*/;

    private double baseTime;

    private ResourceWillSendEvent currentPage;

    private DevToolsDataInstance dataInstance;

    private final Dispatcher dispatcher;

    private ListenerHandle listenerHandle;

    private final PostOrderVisitor[] postOrderVisitors = {};

    private final PreOrderVisitor[] preOrderVisitors = {new TimeNormalizerVisitor(
        this)};

    private final int tabId;

    public Proxy(int tabId) {
      this.baseTime = -1;
      this.tabId = tabId;
      this.dispatcher = createDispatcher(this);
    }

    public final void dispatchPageEvent(PageEvent event) {
      dispatcher.invoke(event.getMethod(), event);
    }

    public void load(DataInstance dataInstance) {
      this.dataInstance = dataInstance.cast();
      connectToDataSource();
    }

    public void resumeMonitoring() {
      connectToDataSource();
    }

    public void setBaseTime(double baseTime) {
      this.baseTime = baseTime;
    }

    public void setProfilingOptions(boolean enableStackTraces,
        boolean enableCpuProfiling) {
      DevTools.setProfilingOptions(tabId, enableStackTraces, enableCpuProfiling);
    }

    public void stopMonitoring() {
      disconnect();
    }

    public void unload() {
      // reset the base time.
      this.baseTime = -1;
      disconnect();
    }

    protected void connectToDataSource() {
      this.listenerHandle = DevTools.getTabEvents(tabId).getPageEvent().addListener(
          new Listener() {
            public void onPageEvent(PageEvent event) {
              dispatchPageEvent(event);
            }
          });
    }

    void connectToDevTools(final DevToolsDataInstance dataInstance) {
      // Connect to the devtools API as the data source.
      if (this.dataInstance == null) {
        this.dataInstance = dataInstance;
      }
      connectToDataSource();
    }

    double getBaseTime() {
      return baseTime;
    }

    void onEventRecord(EventRecord record) {
      dataInstance.onEventRecord(record);
    }

    private void disconnect() {
      if (listenerHandle != null) {
        listenerHandle.removeListener();
      }

      listenerHandle = null;
    }

    /**
     * Normalizes the inputed time to be relative to the base time, and converts
     * the units of the inputed time to milliseconds from seconds.
     * 
     * If baseTime is unset, this method will have the side effect of setting
     * baseTime.
     */
    private double normalizeTime(double seconds) {
      double millis = seconds * 1000;
      if (getBaseTime() < 0) {
        setBaseTime(millis);
      }
      return millis - getBaseTime();
    }

    // Called from JavaScript.
    @SuppressWarnings("unused")
    private void onTimeLineRecord(EventRecord record) {
      assert (dataInstance != null) : "Someone called invoke that wasn't our connect call!";
      // We run visitors to normalize the times for this tree and to do any
      // other transformations we want.
      EventVisitorTraverser.traverse(record.<UiEvent> cast(), preOrderVisitors,
          postOrderVisitors);

      int type = record.getType();

      switch (type) {
        case ResourceWillSendEvent.TYPE:
          // Maybe synthesize a page transition.
          ResourceWillSendEvent start = record.cast();
          // Dispatch a Page Transition if this is a main resource and we are
          // not part of a redirect.
          if (start.isMainResource()) {
            // For redirects, IDs get recycled. We do not want to double page
            // transition for a single main page redirect.
            if ((currentPage == null)
                || (currentPage.getIdentifier() != start.getIdentifier())) {
              // We synthesize the page transition if currentPage is not set, or
              // the IDs dont match.
              currentPage = start;
              onEventRecord(TabChange.create(start.getTime(), start.getUrl()));
            } else if (currentPage.getIdentifier() == start.getIdentifier()) {
              // IDs get recycled across pages. So remember to null out the
              // current page after concluding that the redirect has completed
              currentPage = null;
            }
          }
          break;
        case ResourceResponseEvent.TYPE:
          // For pages with no redirect, we want to ensure that the next page
          // transition goes off.
          currentPage = null;
          break;
      }
      // Forward to the dataInstance.
      onEventRecord(record);
    }

    @SuppressWarnings("unused")
    private void onUpdateResource(int resourceId, JavaScriptObject resource) {
      // We need to normalize times for the resource update since they are
      // absolute times given in seconds.
      UpdateResource update = resource.cast();

      if (update.didTimingChange()) {
        double domContentEventTime = update.getDomContentEventTime();
        double loadTime = update.getLoadEventTime();
        double startTime = update.getStartTime();
        double responseTime = update.getResponseReceivedTime();
        double endTime = update.getEndTime();

        if (domContentEventTime > 0) {
          update.setDomContentEventTime(normalizeTime(domContentEventTime));
        }

        if (loadTime > 0) {
          update.setLoadEventTime(normalizeTime(loadTime));
        }

        if (startTime > 0) {
          update.setStartTime(normalizeTime(startTime));
        }

        if (responseTime > 0) {
          update.setResponseReceivedTime(normalizeTime(responseTime));
        }

        if (endTime > 0) {
          update.setEndTime(normalizeTime(endTime));
        }
      }

      onEventRecord(ResourceUpdateEvent.create(resourceId, update));
    }
  }

  /**
   * Overlay type for our dispatcher used by {@link Proxy}.
   */
  private static class Dispatcher extends JavaScriptObject {
    @SuppressWarnings("unused")
    protected Dispatcher() {
    }

    final native void invoke(String method, JavaScriptObject payload) /*-{
      this[method](payload);
    }-*/;
  }

  /**
   * Constructs and returns a {@link DevToolsDataIstance} after wiring it up to
   * receive events over the extensions-devtools API.
   * 
   * @param tabId the tab that we want to connec to.
   * @return a newly wired up {@link DevToolsDataInstance}.
   */
  public static DevToolsDataInstance create(int tabId) {
    return DataInstance.create(new Proxy(tabId)).cast();
  }

  /**
   * Constructs and returns a {@link DevToolsDataIstance} after wiring it up to
   * receive events over the extensions-devtools API.
   * 
   * @param proxy an externally supplied proxy to act as the record
   *          transformation layer
   * @return a newly wired up {@link DevToolsDataInstance}.
   */
  public static DevToolsDataInstance create(Proxy proxy) {
    return DataInstance.create(proxy).cast();
  }

  protected DevToolsDataInstance() {
  }
}

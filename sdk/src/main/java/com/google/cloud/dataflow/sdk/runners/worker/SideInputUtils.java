/*******************************************************************************
 * Copyright (C) 2015 Google Inc.
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
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.runners.worker;

import static com.google.cloud.dataflow.sdk.util.Structs.getString;

import com.google.api.services.dataflow.model.SideInputInfo;
import com.google.api.services.dataflow.model.Source;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.CloudObject;
import com.google.cloud.dataflow.sdk.util.ExecutionContext;
import com.google.cloud.dataflow.sdk.util.PropertyNames;
import com.google.cloud.dataflow.sdk.util.common.worker.Reader;
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Observer;

/**
 * Utilities for working with side inputs.
 */
public class SideInputUtils {
  static final String SINGLETON_KIND = "singleton";
  static final String COLLECTION_KIND = "collection";

  /**
   * Reads the given side input, producing the contents associated
   * with a {@code PCollectionView}.
   *
   * @throws Exception anything thrown by the delegate {@link Reader}
   * @see com.google.cloud.dataflow.sdk.values.PCollectionView
   */
  public static Object readSideInput(
      PipelineOptions options,
      SideInputInfo sideInputInfo,
      Observer observer,
      ExecutionContext executionContext)
      throws Exception {
    Iterable<Object> elements =
        readSideInputSources(options, sideInputInfo.getSources(), observer, executionContext);
    return readSideInputValue(sideInputInfo.getKind(), elements);
  }

  public static Object readSideInput(
      PipelineOptions options,
      SideInputInfo sideInputInfo,
      ExecutionContext executionContext)
      throws Exception {
    Iterable<Object> elements =
        readSideInputSources(options, sideInputInfo.getSources(), null, executionContext);
    return readSideInputValue(sideInputInfo.getKind(), elements);
  }

  private static Iterable<Object> readSideInputSources(
      PipelineOptions options,
      List<Source> sideInputSources,
      Observer observer,
      ExecutionContext executionContext)
      throws Exception {
    int numSideInputSources = sideInputSources.size();
    if (numSideInputSources == 0) {
      throw new Exception("expecting at least one side input Source");
    } else if (numSideInputSources == 1) {
      return readSideInputSource(options, sideInputSources.get(0), observer, executionContext);
    } else {
      List<Iterable<Object>> shards = new ArrayList<>();
      for (Source sideInputSource : sideInputSources) {
        shards.add(readSideInputSource(options, sideInputSource, observer, executionContext));
      }
      return Iterables.concat(shards);
    }
  }

  private static Iterable<Object> readSideInputSource(
      PipelineOptions options,
      Source sideInputSource,
      Observer observer,
      ExecutionContext executionContext)
      throws Exception {
    // We don't do shuffle sanity check on side inputs, as they don't have to be read completely.
    @SuppressWarnings("unchecked")
    Reader<Object> reader = (Reader<Object>) ReaderFactory.Registry.defaultRegistry().create(
        sideInputSource, options, executionContext, null, null);
    if (observer != null) {
      reader.addObserver(observer);
    }
    return new ReaderIterable<>(reader);
  }

  static Object readSideInputValue(Map<String, Object> sideInputKind, Iterable<Object> elements)
      throws Exception {
    String className = getString(sideInputKind, PropertyNames.OBJECT_TYPE_NAME);
    if (SINGLETON_KIND.equals(className)) {
      Iterator<Object> iter = elements.iterator();
      if (iter.hasNext()) {
        Object elem = iter.next();
        if (!iter.hasNext()) {
          return elem;
        }
      }
      throw new Exception("expecting a singleton side input to have a single value");

    } else if (COLLECTION_KIND.equals(className)) {
      return elements;

    } else {
      throw new Exception("unexpected kind of side input: " + className);
    }
  }


  /////////////////////////////////////////////////////////////////////////////


  static class ReaderIterable<T> implements Iterable<T> {
    final Reader<T> reader;

    public ReaderIterable(Reader<T> reader) {
      this.reader = reader;
    }

    @Override
    public Iterator<T> iterator() {
      try {
        return new ReaderIterator<>(reader.iterator());
      } catch (Exception exn) {
        throw new RuntimeException(exn);
      }
    }
  }

  static class ReaderIterator<T> implements Iterator<T> {
    final Reader.ReaderIterator<T> iterator;

    public ReaderIterator(Reader.ReaderIterator<T> iterator) {
      this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
      try {
        return iterator.hasNext();
      } catch (Exception exn) {
        throw new RuntimeException(exn);
      }
    }

    @Override
    public T next() {
      try {
        return iterator.next();
      } catch (Exception exn) {
        throw new RuntimeException(exn);
      }
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * Builds a {@link SideInputInfo} for a "singleton" side input.
   */
  public static SideInputInfo createSingletonSideInputInfo(Source sideInputSource) {
    SideInputInfo sideInputInfo = new SideInputInfo();
    sideInputInfo.setSources(Arrays.asList(sideInputSource));
    sideInputInfo.setKind(CloudObject.forClassName("singleton"));
    return sideInputInfo;
  }

  /**
   * Builds a {@link SideInputInfo} for a "collection" side input.
   */
  public static SideInputInfo createCollectionSideInputInfo(Source... sideInputSources) {
    SideInputInfo sideInputInfo = new SideInputInfo();
    sideInputInfo.setSources(Arrays.asList(sideInputSources));
    sideInputInfo.setKind(CloudObject.forClassName("collection"));
    return sideInputInfo;
  }
}

/*
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
 */

package com.google.cloud.dataflow.sdk.transforms;

import static com.google.cloud.dataflow.sdk.util.CoderUtils.encodeToByteArray;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.Coder.NonDeterministicException;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.coders.IterableCoder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.runners.DirectPipelineRunner;
import com.google.cloud.dataflow.sdk.runners.DirectPipelineRunner.ValueWithMetadata;
import com.google.cloud.dataflow.sdk.transforms.windowing.DefaultTrigger;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.InvalidWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn;
import com.google.cloud.dataflow.sdk.util.GroupAlsoByWindowsDoFn;
import com.google.cloud.dataflow.sdk.util.ReifyTimestampAndWindowsDoFn;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.WindowedValue.FullWindowedValueCoder;
import com.google.cloud.dataflow.sdk.util.WindowedValue.WindowedValueCoder;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollection.IsBounded;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GroupByKey<K, V>} takes a {@code PCollection<KV<K, V>>},
 * groups the values by key and windows, and returns a
 * {@code PCollection<KV<K, Iterable<V>>>} representing a map from
 * each distinct key and window of the input {@code PCollection} to an
 * {@code Iterable} over all the values associated with that key in
 * the input per window.  Absent repeatedly-firing
 * {@link Window#triggering triggering}, each key in the output
 * {@code PCollection} is unique within each window.
 *
 * <p>{@code GroupByKey} is analogous to converting a multi-map into
 * a uni-map, and related to {@code GROUP BY} in SQL.  It corresponds
 * to the "shuffle" step between the Mapper and the Reducer in the
 * MapReduce framework.
 *
 * <p>Two keys of type {@code K} are compared for equality
 * <b>not</b> by regular Java {@link Object#equals}, but instead by
 * first encoding each of the keys using the {@code Coder} of the
 * keys of the input {@code PCollection}, and then comparing the
 * encoded bytes.  This admits efficient parallel evaluation.  Note that
 * this requires that the {@code Coder} of the keys be deterministic (see
 * {@link Coder#verifyDeterministic()}).  If the key {@code Coder} is not
 * deterministic, an exception is thrown at pipeline construction time.
 *
 * <p>By default, the {@code Coder} of the keys of the output
 * {@code PCollection} is the same as that of the keys of the input,
 * and the {@code Coder} of the elements of the {@code Iterable}
 * values of the output {@code PCollection} is the same as the
 * {@code Coder} of the values of the input.
 *
 * <p>Example of use:
 * <pre> {@code
 * PCollection<KV<String, Doc>> urlDocPairs = ...;
 * PCollection<KV<String, Iterable<Doc>>> urlToDocs =
 *     urlDocPairs.apply(GroupByKey.<String, Doc>create());
 * PCollection<R> results =
 *     urlToDocs.apply(ParDo.of(new DoFn<KV<String, Iterable<Doc>>, R>() {
 *       public void processElement(ProcessContext c) {
 *         String url = c.element().getKey();
 *         Iterable<Doc> docsWithThatUrl = c.element().getValue();
 *         ... process all docs having that url ...
 *       }}));
 * } </pre>
 *
 * <p>{@code GroupByKey} is a key primitive in data-parallel
 * processing, since it is the main way to efficiently bring
 * associated data together into one location.  It is also a key
 * determiner of the performance of a data-parallel pipeline.
 *
 * <p>See {@link com.google.cloud.dataflow.sdk.transforms.join.CoGroupByKey}
 * for a way to group multiple input PCollections by a common key at once.
 *
 * <p>See {@link Combine.PerKey} for a common pattern of
 * {@code GroupByKey} followed by {@link Combine.GroupedValues}.
 *
 * <p>When grouping, windows that can be merged according to the {@link WindowFn}
 * of the input {@code PCollection} will be merged together, and a window pane
 * corresponding to the new, merged window will be created. The items in this pane
 * will be emitted when a trigger fires. By default this will be when the input
 * sources estimate there will be no more data for the window. See
 * {@link com.google.cloud.dataflow.sdk.transforms.windowing.AfterWatermark}
 * for details on the estimation.
 *
 * <p>The timestamp for each emitted pane is determined by the
 * {@link Window.Bound#withOutputTimeFn windowing operation}.
 * The output {@code PCollection} will have the same {@link WindowFn}
 * as the input.
 *
 * <p>If the input {@code PCollection} contains late data (see
 * {@link com.google.cloud.dataflow.sdk.io.PubsubIO.Read.Bound#timestampLabel}
 * for an example of how this can occur) or the
 * {@link Window#triggering requested TriggerFn} can fire before
 * the watermark, then there may be multiple elements
 * output by a {@code GroupByKey} that correspond to the same key and window.
 *
 * <p>If the {@link WindowFn} of the input requires merging, it is not
 * valid to apply another {@code GroupByKey} without first applying a new
 * {@link WindowFn} or applying {@link Window#remerge()}.
 *
 * @param <K> the type of the keys of the input and output
 * {@code PCollection}s
 * @param <V> the type of the values of the input {@code PCollection}
 * and the elements of the {@code Iterable}s in the output
 * {@code PCollection}
 */
public class GroupByKey<K, V>
    extends PTransform<PCollection<KV<K, V>>,
                       PCollection<KV<K, Iterable<V>>>> {

  private final boolean fewKeys;

  private GroupByKey(boolean fewKeys) {
    this.fewKeys = fewKeys;
  }

  /**
   * Returns a {@code GroupByKey<K, V>} {@code PTransform}.
   *
   * @param <K> the type of the keys of the input and output
   * {@code PCollection}s
   * @param <V> the type of the values of the input {@code PCollection}
   * and the elements of the {@code Iterable}s in the output
   * {@code PCollection}
   */
  public static <K, V> GroupByKey<K, V> create() {
    return new GroupByKey<>(false);
  }

  /**
   * Returns a {@code GroupByKey<K, V>} {@code PTransform}.
   *
   * @param <K> the type of the keys of the input and output
   * {@code PCollection}s
   * @param <V> the type of the values of the input {@code PCollection}
   * and the elements of the {@code Iterable}s in the output
   * {@code PCollection}
   * @param fewKeys whether it groups just few keys.
   */
  static <K, V> GroupByKey<K, V> create(boolean fewKeys) {
    return new GroupByKey<>(fewKeys);
  }

  /**
   * Returns whether it groups just few keys.
   */
  public boolean fewKeys() {
    return fewKeys;
  }

  /////////////////////////////////////////////////////////////////////////////

  public static void applicableTo(PCollection<?> input) {
    WindowingStrategy<?, ?> windowingStrategy = input.getWindowingStrategy();
    // Verify that the input PCollection is bounded, or that there is windowing/triggering being
    // used. Without this, the watermark (at end of global window) will never be reached.
    if (windowingStrategy.getWindowFn() instanceof GlobalWindows
        && windowingStrategy.getTrigger().getSpec() instanceof DefaultTrigger
        && input.isBounded() != IsBounded.BOUNDED) {
      throw new IllegalStateException("GroupByKey cannot be applied to non-bounded PCollection in "
          + "the GlobalWindow without a trigger. Use a Window.into or Window.triggering transform "
          + "prior to GroupByKey.");
    }

    // Validate the window merge function.
    if (windowingStrategy.getWindowFn() instanceof InvalidWindows) {
      String cause = ((InvalidWindows<?>) windowingStrategy.getWindowFn()).getCause();
      throw new IllegalStateException(
          "GroupByKey must have a valid Window merge function.  "
              + "Invalid because: " + cause);
    }
  }

  @Override
  public void validate(PCollection<KV<K, V>> input) {
    applicableTo(input);

    // Verify that the input Coder<KV<K, V>> is a KvCoder<K, V>, and that
    // the key coder is deterministic.
    Coder<K> keyCoder = getKeyCoder(input.getCoder());
    try {
      keyCoder.verifyDeterministic();
    } catch (NonDeterministicException e) {
      throw new IllegalStateException(
          "the keyCoder of a GroupByKey must be deterministic", e);
    }
  }

  public WindowingStrategy<?, ?> updateWindowingStrategy(WindowingStrategy<?, ?> inputStrategy) {
    WindowFn<?, ?> inputWindowFn = inputStrategy.getWindowFn();
    if (!inputWindowFn.isNonMerging()) {
      // Prevent merging windows again, without explicit user
      // involvement, e.g., by Window.into() or Window.remerge().
      inputWindowFn = new InvalidWindows<>(
          "WindowFn has already been consumed by previous GroupByKey", inputWindowFn);
    }

    // We also switch to the continuation trigger associated with the current trigger.
    return inputStrategy
        .withWindowFn(inputWindowFn)
        .withTrigger(inputStrategy.getTrigger().getSpec().getContinuationTrigger());
  }

  @Override
  public PCollection<KV<K, Iterable<V>>> apply(PCollection<KV<K, V>> input) {
    // This operation groups by the combination of key and window,
    // merging windows as needed, using the windows assigned to the
    // key/value input elements and the window merge operation of the
    // window function associated with the input PCollection.
    WindowingStrategy<?, ?> windowingStrategy = input.getWindowingStrategy();

    // By default, implement GroupByKey[AndWindow] via a series of lower-level
    // operations.
    return input
        // Make each input element's timestamp and assigned windows
        // explicit, in the value part.
        .apply(new ReifyTimestampsAndWindows<K, V>())

        // Group by just the key.
        // Combiner lifting will not happen regardless of the disallowCombinerLifting value.
        // There will be no combiners right after the GroupByKeyOnly because of the two ParDos
        // introduced in here.
        .apply(new GroupByKeyOnly<K, WindowedValue<V>>())

        // Sort each key's values by timestamp. GroupAlsoByWindow requires
        // its input to be sorted by timestamp.
        .apply(new SortValuesByTimestamp<K, V>())

        // Group each key's values by window, merging windows as needed.
        .apply(new GroupAlsoByWindow<K, V>(windowingStrategy))

        // And update the windowing strategy as appropriate.
        .setWindowingStrategyInternal(updateWindowingStrategy(windowingStrategy));
  }

  @Override
  protected Coder<KV<K, Iterable<V>>> getDefaultOutputCoder(PCollection<KV<K, V>> input) {
    return getOutputKvCoder(input.getCoder());
  }

  /**
   * Returns the {@code Coder} of the input to this transform, which
   * should be a {@code KvCoder}.
   */
  @SuppressWarnings("unchecked")
  static <K, V> KvCoder<K, V> getInputKvCoder(Coder<KV<K, V>> inputCoder) {
    if (!(inputCoder instanceof KvCoder)) {
      throw new IllegalStateException(
          "GroupByKey requires its input to use KvCoder");
    }
    return (KvCoder<K, V>) inputCoder;
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Returns the {@code Coder} of the keys of the input to this
   * transform, which is also used as the {@code Coder} of the keys of
   * the output of this transform.
   */
  static <K, V> Coder<K> getKeyCoder(Coder<KV<K, V>> inputCoder) {
    return getInputKvCoder(inputCoder).getKeyCoder();
  }

  /**
   * Returns the {@code Coder} of the values of the input to this transform.
   */
  static <K, V> Coder<V> getInputValueCoder(Coder<KV<K, V>> inputCoder) {
    return getInputKvCoder(inputCoder).getValueCoder();
  }

  /**
   * Returns the {@code Coder} of the {@code Iterable} values of the
   * output of this transform.
   */
  static <K, V> Coder<Iterable<V>> getOutputValueCoder(Coder<KV<K, V>> inputCoder) {
    return IterableCoder.of(getInputValueCoder(inputCoder));
  }

  /**
   * Returns the {@code Coder} of the output of this transform.
   */
  static <K, V> KvCoder<K, Iterable<V>> getOutputKvCoder(Coder<KV<K, V>> inputCoder) {
    return KvCoder.of(getKeyCoder(inputCoder), getOutputValueCoder(inputCoder));
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Helper transform that makes timestamps and window assignments
   * explicit in the value part of each key/value pair.
   */
  public static class ReifyTimestampsAndWindows<K, V>
      extends PTransform<PCollection<KV<K, V>>,
                         PCollection<KV<K, WindowedValue<V>>>> {
    @Override
    public PCollection<KV<K, WindowedValue<V>>> apply(
        PCollection<KV<K, V>> input) {
      @SuppressWarnings("unchecked")
      KvCoder<K, V> inputKvCoder = (KvCoder<K, V>) input.getCoder();
      Coder<K> keyCoder = inputKvCoder.getKeyCoder();
      Coder<V> inputValueCoder = inputKvCoder.getValueCoder();
      Coder<WindowedValue<V>> outputValueCoder = FullWindowedValueCoder.of(
          inputValueCoder, input.getWindowingStrategy().getWindowFn().windowCoder());
      Coder<KV<K, WindowedValue<V>>> outputKvCoder =
          KvCoder.of(keyCoder, outputValueCoder);
      return input.apply(ParDo.of(new ReifyTimestampAndWindowsDoFn<K, V>()))
          .setCoder(outputKvCoder);
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * Helper transform that sorts the values associated with each key
   * by timestamp.
   */
  public static class SortValuesByTimestamp<K, V>
      extends PTransform<PCollection<KV<K, Iterable<WindowedValue<V>>>>,
                         PCollection<KV<K, Iterable<WindowedValue<V>>>>> {
    @Override
    public PCollection<KV<K, Iterable<WindowedValue<V>>>> apply(
        PCollection<KV<K, Iterable<WindowedValue<V>>>> input) {
      return input.apply(ParDo.of(
          new DoFn<KV<K, Iterable<WindowedValue<V>>>,
                   KV<K, Iterable<WindowedValue<V>>>>() {
            @Override
            public void processElement(ProcessContext c) {
              KV<K, Iterable<WindowedValue<V>>> kvs = c.element();
              K key = kvs.getKey();
              Iterable<WindowedValue<V>> unsortedValues = kvs.getValue();
              List<WindowedValue<V>> sortedValues = new ArrayList<>();
              for (WindowedValue<V> value : unsortedValues) {
                sortedValues.add(value);
              }
              Collections.sort(sortedValues,
                               new Comparator<WindowedValue<V>>() {
                  @Override
                  public int compare(WindowedValue<V> e1, WindowedValue<V> e2) {
                    return e1.getTimestamp().compareTo(e2.getTimestamp());
                  }
                });
              c.output(KV.<K, Iterable<WindowedValue<V>>>of(key, sortedValues));
            }}))
          .setCoder(input.getCoder());
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * Helper transform that takes a collection of timestamp-ordered
   * values associated with each key, groups the values by window,
   * combines windows as needed, and for each window in each key,
   * outputs a collection of key/value-list pairs implicitly assigned
   * to the window and with the timestamp derived from that window.
   */
  public static class GroupAlsoByWindow<K, V>
      extends PTransform<PCollection<KV<K, Iterable<WindowedValue<V>>>>,
                         PCollection<KV<K, Iterable<V>>>> {
    private final WindowingStrategy<?, ?> windowingStrategy;

    public GroupAlsoByWindow(WindowingStrategy<?, ?> windowingStrategy) {
      this.windowingStrategy = windowingStrategy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PCollection<KV<K, Iterable<V>>> apply(
        PCollection<KV<K, Iterable<WindowedValue<V>>>> input) {
      @SuppressWarnings("unchecked")
      KvCoder<K, Iterable<WindowedValue<V>>> inputKvCoder =
          (KvCoder<K, Iterable<WindowedValue<V>>>) input.getCoder();

      Coder<K> keyCoder = inputKvCoder.getKeyCoder();
      Coder<Iterable<WindowedValue<V>>> inputValueCoder =
          inputKvCoder.getValueCoder();

      IterableCoder<WindowedValue<V>> inputIterableValueCoder =
          (IterableCoder<WindowedValue<V>>) inputValueCoder;
      Coder<WindowedValue<V>> inputIterableElementCoder =
          inputIterableValueCoder.getElemCoder();
      WindowedValueCoder<V> inputIterableWindowedValueCoder =
          (WindowedValueCoder<V>) inputIterableElementCoder;

      Coder<V> inputIterableElementValueCoder =
          inputIterableWindowedValueCoder.getValueCoder();
      Coder<Iterable<V>> outputValueCoder =
          IterableCoder.of(inputIterableElementValueCoder);
      Coder<KV<K, Iterable<V>>> outputKvCoder =
          KvCoder.of(keyCoder, outputValueCoder);

      GroupAlsoByWindowsDoFn<K, V, Iterable<V>, ?> fn =
          GroupAlsoByWindowsDoFn.createForIterable(
              windowingStrategy, inputIterableElementValueCoder);

      return input.apply(ParDo.of(fn)).setCoder(outputKvCoder);
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * Primitive helper transform that groups by key only, ignoring any
   * window assignments.
   */
  public static class GroupByKeyOnly<K, V>
      extends PTransform<PCollection<KV<K, V>>,
                         PCollection<KV<K, Iterable<V>>>> {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public PCollection<KV<K, Iterable<V>>> apply(PCollection<KV<K, V>> input) {
      return PCollection.<KV<K, Iterable<V>>>createPrimitiveOutputInternal(
          input.getPipeline(), input.getWindowingStrategy(), input.isBounded());
    }

    /**
     * Returns the {@code Coder} of the input to this transform, which
     * should be a {@code KvCoder}.
     */
    @SuppressWarnings("unchecked")
    KvCoder<K, V> getInputKvCoder(Coder<KV<K, V>> inputCoder) {
      if (!(inputCoder instanceof KvCoder)) {
        throw new IllegalStateException(
            "GroupByKey requires its input to use KvCoder");
      }
      return (KvCoder<K, V>) inputCoder;
    }

    @Override
    protected Coder<KV<K, Iterable<V>>> getDefaultOutputCoder(PCollection<KV<K, V>> input) {
      return GroupByKey.getOutputKvCoder(input.getCoder());
    }
  }


  /////////////////////////////////////////////////////////////////////////////

  static {
    registerWithDirectPipelineRunner();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <K, V> void registerWithDirectPipelineRunner() {
    DirectPipelineRunner.registerDefaultTransformEvaluator(
        GroupByKeyOnly.class,
        new DirectPipelineRunner.TransformEvaluator<GroupByKeyOnly>() {
          @Override
          public void evaluate(
              GroupByKeyOnly transform,
              DirectPipelineRunner.EvaluationContext context) {
            evaluateHelper(transform, context);
          }
        });
  }

  private static <K, V> void evaluateHelper(
      GroupByKeyOnly<K, V> transform,
      DirectPipelineRunner.EvaluationContext context) {
    PCollection<KV<K, V>> input = context.getInput(transform);

    List<ValueWithMetadata<KV<K, V>>> inputElems =
        context.getPCollectionValuesWithMetadata(input);

    Coder<K> keyCoder = GroupByKey.getKeyCoder(input.getCoder());

    Map<GroupingKey<K>, List<V>> groupingMap = new HashMap<>();

    for (ValueWithMetadata<KV<K, V>> elem : inputElems) {
      K key = elem.getValue().getKey();
      V value = elem.getValue().getValue();
      byte[] encodedKey;
      try {
        encodedKey = encodeToByteArray(keyCoder, key);
      } catch (CoderException exn) {
        // TODO: Put in better element printing:
        // truncate if too long.
        throw new IllegalArgumentException(
            "unable to encode key " + key + " of input to " + transform +
            " using " + keyCoder,
            exn);
      }
      GroupingKey<K> groupingKey = new GroupingKey<>(key, encodedKey);
      List<V> values = groupingMap.get(groupingKey);
      if (values == null) {
        values = new ArrayList<V>();
        groupingMap.put(groupingKey, values);
      }
      values.add(value);
    }

    List<ValueWithMetadata<KV<K, Iterable<V>>>> outputElems =
        new ArrayList<>();
    for (Map.Entry<GroupingKey<K>, List<V>> entry : groupingMap.entrySet()) {
      GroupingKey<K> groupingKey = entry.getKey();
      K key = groupingKey.getKey();
      List<V> values = entry.getValue();
      values = context.randomizeIfUnordered(values, true /* inPlaceAllowed */);
      outputElems.add(ValueWithMetadata
                      .of(WindowedValue.valueInEmptyWindows(KV.<K, Iterable<V>>of(key, values)))
                      .withKey(key));
    }

    context.setPCollectionValuesWithMetadata(context.getOutput(transform),
                                             outputElems);
  }

  private static class GroupingKey<K> {
    private K key;
    private byte[] encodedKey;

    public GroupingKey(K key, byte[] encodedKey) {
      this.key = key;
      this.encodedKey = encodedKey;
    }

    public K getKey() {
      return key;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof GroupingKey) {
        GroupingKey<?> that = (GroupingKey<?>) o;
        return Arrays.equals(this.encodedKey, that.encodedKey);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(encodedKey);
    }
  }
}

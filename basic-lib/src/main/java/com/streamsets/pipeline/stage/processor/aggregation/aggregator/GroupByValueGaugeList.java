/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.processor.aggregation.aggregator;

import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.stage.processor.aggregation.WindowType;

import java.util.AbstractSequentialList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * Minimal List implementation to expose real time group-by aggregator data through a gauge.
 */
public class GroupByValueGaugeList extends AbstractSequentialList {
  private final WindowType windowType;
  private final GroupByAggregator aggregator;

  public GroupByValueGaugeList(WindowType windowType, GroupByAggregator aggregator) {
    this.windowType = windowType;
    this.aggregator = aggregator;
  }

  @Override
  public ListIterator listIterator(int index) {
    Set<String> elements = new HashSet<>();

    List<AggregatorDataProvider.DataWindow> dataWindows = aggregator.getDataProvider().getDataWindows();

    // the datawindows is a snapshot at the time of assignment except for the live window that may be updated or
    // or become a close window.
    // the complete groupby gauge value set will be constructed from the datawindows snapshot.

    // finding the group-by elements in all data windows
    for (AggregatorDataProvider.DataWindow dataWindow : dataWindows) {
      Map<String, Aggregator> childrenAgregators = dataWindow.getGroupByElementAggregators(aggregator);
      if (childrenAgregators != null) {
        elements.addAll(childrenAgregators.keySet());
      }
    }

    // we need to sort them so they always show up orderly
    List<String> elementsSorted = new ArrayList<>(elements);
    Collections.sort(elementsSorted);

    List<Map<String, Map<String, Number>>> groupByElementGauges = new ArrayList<>();
    for (String element : elementsSorted) {
      groupByElementGauges.add(ImmutableMap.of(element, createGroupByElementEntry(aggregator, element, dataWindows)));
    }
    return groupByElementGauges.listIterator();
  }

  protected Map<String, Number> createGroupByElementEntry(
      GroupByAggregator aggregator, String elementName, List<AggregatorDataProvider.DataWindow> dataWindows
  ) {
    Map<String, Number> data = new LinkedHashMap<>();

    if (windowType == WindowType.ROLLING) {
      //scanning all datawindows (they are in time order) and creating the structure for metrics UI
      for (AggregatorDataProvider.DataWindow dataWindow : dataWindows) {
        // get the aggregator for the groupby element from the datawindow
        Map<String, Aggregator> childrenAggregator = dataWindow.getGroupByElementAggregators(aggregator);
        if (childrenAggregator != null) {
          Aggregator groupByElementAggregator = childrenAggregator.get(elementName);

          if (groupByElementAggregator != null) {
            // if there was a groupby element aggregator in the data window, get the corresponding value
            AggregatorData groupByElementAggregatorData = dataWindow.getData(groupByElementAggregator);
            Number value = (Number) groupByElementAggregatorData.get();

            // added it to the result map
            data.put(Long.toString(dataWindow.getEndTimeMillis()), value);
          }
        }
      }
    } else {
      //time of current open timewindow
      long endTimeMillis= dataWindows.get(dataWindows.size() - 1).getEndTimeMillis();

      // we need to aggregate all microwindows to produce one
      AggregatorData aggregatorDataForElement = null;

      //scanning all datawindows (they are in time order) and creating the structure for metrics UI
      for (AggregatorDataProvider.DataWindow dataWindow : dataWindows) {
        // get the aggregator for the groupby element from the datawindow
        Map<String, Aggregator> childrenAggregator = dataWindow.getGroupByElementAggregators(aggregator);
        if (childrenAggregator != null) {
          Aggregator groupByElementAggregator = childrenAggregator.get(elementName);

          if (groupByElementAggregator != null) {
            if (aggregatorDataForElement == null) {
              aggregatorDataForElement = groupByElementAggregator.createAggregatorData(endTimeMillis);
            }

            // if there was a groupby element aggregator in the data window, get the corresponding value
            AggregatorData groupByElementAggregatorData = dataWindow.getData(groupByElementAggregator);

            aggregatorDataForElement.aggregate(groupByElementAggregatorData.getAggregatable());

          }
        }
      }

      Number value = (Number) aggregatorDataForElement.get();
      // added it to the result map
      data.put(Long.toString(endTimeMillis), value);

    }
    return data;
  }

  @Override
  public int size() {
    // returns a dummy non-empty value to trigger the scanning when JSON serializing
    return 1;
  }

}

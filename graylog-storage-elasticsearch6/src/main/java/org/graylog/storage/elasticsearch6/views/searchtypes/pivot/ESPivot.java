/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.storage.elasticsearch6.views.searchtypes.pivot;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.searchbox.core.SearchResult;
import io.searchbox.core.search.aggregation.Aggregation;
import io.searchbox.core.search.aggregation.MetricAggregation;
import one.util.streamex.EntryStream;
import org.graylog.plugins.views.search.Query;
import org.graylog.plugins.views.search.SearchJob;
import org.graylog.plugins.views.search.SearchType;
import org.graylog.plugins.views.search.searchtypes.pivot.BucketSpec;
import org.graylog.plugins.views.search.searchtypes.pivot.Pivot;
import org.graylog.plugins.views.search.searchtypes.pivot.PivotResult;
import org.graylog.plugins.views.search.searchtypes.pivot.PivotSpec;
import org.graylog.plugins.views.search.searchtypes.pivot.SeriesSpec;
import org.graylog.shaded.elasticsearch6.org.elasticsearch.search.aggregations.AggregationBuilder;
import org.graylog.shaded.elasticsearch6.org.elasticsearch.search.aggregations.AggregationBuilders;
import org.graylog.shaded.elasticsearch6.org.elasticsearch.search.aggregations.metrics.max.MaxAggregationBuilder;
import org.graylog.shaded.elasticsearch6.org.elasticsearch.search.aggregations.metrics.min.MinAggregationBuilder;
import org.graylog.shaded.elasticsearch6.org.elasticsearch.search.builder.SearchSourceBuilder;
import org.graylog.storage.elasticsearch6.views.ESGeneratedQueryContext;
import org.graylog.storage.elasticsearch6.views.searchtypes.ESSearchTypeHandler;
import org.graylog2.plugin.indexer.searches.timeranges.AbsoluteRange;
import org.graylog2.plugin.indexer.searches.timeranges.InvalidRangeParametersException;
import org.graylog2.plugin.indexer.searches.timeranges.RelativeRange;
import org.graylog2.plugin.indexer.searches.timeranges.TimeRange;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class ESPivot implements ESSearchTypeHandler<Pivot> {
    private static final Logger LOG = LoggerFactory.getLogger(ESPivot.class);
    private final Map<String, ESPivotBucketSpecHandler<? extends BucketSpec, ? extends Aggregation>> bucketHandlers;
    private final Map<String, ESPivotSeriesSpecHandler<? extends SeriesSpec, ? extends Aggregation>> seriesHandlers;
    private static final TimeRange ALL_MESSAGES_TIMERANGE = allMessagesTimeRange();

    private static TimeRange allMessagesTimeRange() {
        try {
            return RelativeRange.create(0);
        } catch (InvalidRangeParametersException e){
            LOG.error("Unable to instantiate all messages timerange: ", e);
        }
        return null;
    }

    @Inject
    public ESPivot(Map<String, ESPivotBucketSpecHandler<? extends BucketSpec, ? extends Aggregation>> bucketHandlers,
                   Map<String, ESPivotSeriesSpecHandler<? extends SeriesSpec, ? extends Aggregation>> seriesHandlers) {
        this.bucketHandlers = bucketHandlers;
        this.seriesHandlers = seriesHandlers;
    }

    @Override
    public void doGenerateQueryPart(SearchJob job, Query query, Pivot pivot, ESGeneratedQueryContext queryContext) {
        LOG.debug("Generating aggregation for {}", pivot);
        final SearchSourceBuilder searchSourceBuilder = queryContext.searchSourceBuilder(pivot);

        final Map<Object, Object> contextMap = queryContext.contextMap();
        final AggTypes aggTypes = new AggTypes();
        contextMap.put(pivot.id(), aggTypes);

        // holds the initial level aggregation to be added to the query
        AggregationBuilder topLevelAggregation = null;
        // holds the last complete bucket aggregation into which subsequent buckets get added
        AggregationBuilder previousAggregation = null;

        // add global rollup series if those were requested
        if (pivot.rollup()) {
            seriesStream(pivot, queryContext, "global rollup")
                    .forEach(previousAggregation != null ? previousAggregation::subAggregation : searchSourceBuilder::aggregation);
        }

        final Iterator<BucketSpec> rowBuckets = pivot.rowGroups().iterator();
        while (rowBuckets.hasNext()) {
            final BucketSpec bucketSpec = rowBuckets.next();

            final String name = queryContext.nextName();
            LOG.debug("Creating row group aggregation '{}' as {}", bucketSpec.type(), name);
            final ESPivotBucketSpecHandler<? extends PivotSpec, ? extends Aggregation> handler = bucketHandlers.get(bucketSpec.type());
            if (handler == null) {
                throw new IllegalArgumentException("Unknown row_group type " + bucketSpec.type());
            }
            final Optional<AggregationBuilder> generatedAggregation = handler.createAggregation(name, pivot, bucketSpec, this, queryContext, query);
            if (generatedAggregation.isPresent()) {
                final AggregationBuilder aggregationBuilder = generatedAggregation.get();
                if (topLevelAggregation == null) {
                    topLevelAggregation = aggregationBuilder;
                }
                // always insert the series for the final row group, or for each one if explicit rollup was requested
                if (!rowBuckets.hasNext() || pivot.rollup()) {
                    seriesStream(pivot, queryContext, !rowBuckets.hasNext() ? "leaf row" : "row rollup")
                            .forEach(aggregationBuilder::subAggregation);
                }
                if (previousAggregation != null) {
                    previousAggregation.subAggregation(aggregationBuilder);
                } else {
                    searchSourceBuilder.aggregation(aggregationBuilder);
                }
                previousAggregation = aggregationBuilder;
            }
        }
        final Iterator<BucketSpec> colBuckets = pivot.columnGroups().iterator();
        while (colBuckets.hasNext()) {
            final BucketSpec bucketSpec = colBuckets.next();

            final String name = queryContext.nextName();
            LOG.debug("Creating column group aggregation '{}' as {}", bucketSpec.type(), name);
            final ESPivotBucketSpecHandler<? extends PivotSpec, ? extends Aggregation> handler = bucketHandlers.get(bucketSpec.type());
            if (handler == null) {
                throw new IllegalArgumentException("Unknown column_group type " + bucketSpec.type());
            }
            final Optional<AggregationBuilder> generatedAggregation = handler.createAggregation(name, pivot, bucketSpec, this, queryContext, query);
            if (generatedAggregation.isPresent()) {
                final AggregationBuilder aggregationBuilder = generatedAggregation.get();
                // always insert the series for the final row group, or for each one if explicit rollup was requested
                if (!colBuckets.hasNext() || pivot.rollup()) {
                    seriesStream(pivot, queryContext, !colBuckets.hasNext() ? "leaf column" : "column rollup")
                            .forEach(aggregationBuilder::subAggregation);
                }
                if (previousAggregation != null) {
                    previousAggregation.subAggregation(aggregationBuilder);
                } else {
                    searchSourceBuilder.aggregation(aggregationBuilder);
                }
                previousAggregation = aggregationBuilder;
            }
        }

        final MinAggregationBuilder startTimestamp = AggregationBuilders.min("timestamp-min").field("timestamp");
        final MaxAggregationBuilder endTimestamp = AggregationBuilders.max("timestamp-max").field("timestamp");

        searchSourceBuilder.aggregation(startTimestamp);
        searchSourceBuilder.aggregation(endTimestamp);

        if (topLevelAggregation == null) {
            LOG.debug("No aggregations generated for {}", pivot);
        }
    }

    private Stream<AggregationBuilder> seriesStream(Pivot pivot, ESGeneratedQueryContext queryContext, String reason) {
        return EntryStream.of(pivot.series())
                .mapKeyValue((integer, seriesSpec) -> {
                    final String seriesName = queryContext.seriesName(seriesSpec, pivot);
                    LOG.debug("Adding {} series '{}' with name '{}'", reason, seriesSpec.type(), seriesName);
                    final ESPivotSeriesSpecHandler<? extends SeriesSpec, ? extends Aggregation> esPivotSeriesSpecHandler = seriesHandlers.get(seriesSpec.type());
                    if (esPivotSeriesSpecHandler == null) {
                        throw new IllegalArgumentException("No series handler registered for: " + seriesSpec.type());
                    }
                    return esPivotSeriesSpecHandler.createAggregation(seriesName, pivot, seriesSpec, this, queryContext);
                })
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private boolean isAllMessagesTimeRange(TimeRange timeRange) {
        return ALL_MESSAGES_TIMERANGE.equals(timeRange);
    }

    private AbsoluteRange extractEffectiveTimeRange(SearchResult queryResult, Query query, Pivot pivot) {
        if (queryResult.getTotal() != 0) {
            return getAbsoluteRangeFromAggregations(queryResult, query, pivot);
        } else {
            return getAbsoluteRangeFromPivot(query, pivot);
        }
    }

    private AbsoluteRange getAbsoluteRangeFromPivot(final Query query, final Pivot pivot) {
        final TimeRange pivotRange = query.effectiveTimeRange(pivot);
        return AbsoluteRange.create(pivotRange.getFrom(), pivotRange.getTo());
    }

    private AbsoluteRange getAbsoluteRangeFromAggregations(final SearchResult queryResult, final Query query, final Pivot pivot) {
        final Double from = queryResult.getAggregations().getMinAggregation("timestamp-min").getMin();
        final Double to = queryResult.getAggregations().getMaxAggregation("timestamp-max").getMax();
        final TimeRange pivotRange = query.effectiveTimeRange(pivot);
        return AbsoluteRange.create(
                isAllMessagesTimeRange(pivotRange) && from != null
                        ? new DateTime(from.longValue(), DateTimeZone.UTC)
                        : pivotRange.getFrom(),
                isAllMessagesTimeRange(pivotRange) && to != null
                        ? new DateTime(to.longValue(), DateTimeZone.UTC)
                        : pivotRange.getTo()
        );
    }

    @Override
    public SearchType.Result doExtractResult(SearchJob job, Query query, Pivot pivot, SearchResult queryResult, MetricAggregation aggregations, ESGeneratedQueryContext queryContext) {
        final AbsoluteRange effectiveTimerange = extractEffectiveTimeRange(queryResult, query, pivot);

        final PivotResult.Builder resultBuilder = PivotResult.builder()
                .id(pivot.id())
                .effectiveTimerange(effectiveTimerange)
                .total(extractDocumentCount(queryResult, pivot, queryContext));

        // pivot results are a table where cells can contain multiple "values" and not only scalars:
        // each combination of row and column groups can contain all series (if rollup is true)
        // if rollup is false, only the "leaf" components contain the series
        // in the elasticsearch result, rows and columns are simply nested aggregations (first aggregations from rows, then from columns)
        // with metric aggregations on the corresponding levels.
        // first we iterate over all row groups (whose values generate a "key array", corresponding to the nesting level)
        // once we exhaust the row groups, we descend into the columns, which get added as values to their corresponding rows
        // on each nesting level and combination we have to check for series which we also add as values to the containing row

        processRows(resultBuilder, queryResult, queryContext, pivot, pivot.rowGroups(), new ArrayDeque<>(), aggregations);

        return pivot.name().map(resultBuilder::name).orElse(resultBuilder).build();
    }

    private long extractDocumentCount(SearchResult queryResult, Pivot pivot, ESGeneratedQueryContext queryContext) {
        return queryResult.getTotal();
    }


    /*
        results from elasticsearch are nested so we need to recurse into the aggregation tree, but our result is a table, thus we need
        to keep track of the current row keys manually
         */
    private void processRows(PivotResult.Builder resultBuilder,
                             SearchResult searchResult,
                             ESGeneratedQueryContext queryContext,
                             Pivot pivot,
                             List<BucketSpec> remainingRows,
                             ArrayDeque<String> rowKeys,
                             MetricAggregation aggregation) {
        if (remainingRows.isEmpty()) {
            // this is the last row group, so we need to fork into the columns if they exist.
            // being here also means that `rowKeys` contains the maximum number of parts, one for each combination of row bucket keys
            // we will always add the series for this bucket, because that's the entire point of row groups

            final PivotResult.Row.Builder rowBuilder = PivotResult.Row.builder().key(ImmutableList.copyOf(rowKeys));
            // do the same for columns as we did for the rows
            processColumns(rowBuilder, searchResult, queryContext, pivot, pivot.columnGroups(), new ArrayDeque<>(), aggregation);

            // also add the series for the entire row
            // columnKeys is empty, because this is a rollup per row bucket, thus for all columns in that bucket (IOW it's not a leaf!)
            if (pivot.rollup()) {
                processSeries(rowBuilder, searchResult, queryContext, pivot, new ArrayDeque<>(), aggregation, true, "row-leaf");
            }
            resultBuilder.addRow(rowBuilder.source("leaf").build());
        } else {
            // this is not a leaf for the rows, so we add its key to the rowKeys and descend into the aggregation tree
            // afterwards we'll check if we need to add rollup for intermediate buckets. not all clients need them so they can request
            // to not calculate them
            final BucketSpec currentBucket = remainingRows.get(0);

            // this handler should never be missing, because we used it above to generate the query
            // if it is missing for some weird reason, it's ok to fail hard here
            final ESPivotBucketSpecHandler<? extends PivotSpec, ? extends Aggregation> handler = bucketHandlers.get(currentBucket.type());
            final Aggregation aggregationResult = handler.extractAggregationFromResult(pivot, currentBucket, aggregation, queryContext);
            final Stream<ESPivotBucketSpecHandler.Bucket> bucketStream = handler.handleResult(pivot, currentBucket, searchResult, aggregationResult, this, queryContext);
            // for each bucket, recurse and eventually collect all the row keys. once we reach a leaf, we'll end up in the other if branch above
            bucketStream.forEach(bucket -> {
                // push the bucket's key and use its aggregation as the new source for sub-aggregations
                rowKeys.addLast(bucket.key());
                processRows(resultBuilder, searchResult, queryContext, pivot, tail(remainingRows), rowKeys, bucket.aggregation());
                rowKeys.removeLast();
            });
            // also add the series for this row key if the client wants rollups
            if (pivot.rollup()) {
                final PivotResult.Row.Builder rowBuilder = PivotResult.Row.builder().key(ImmutableList.copyOf(rowKeys));
                // columnKeys is empty, because this is a rollup per row bucket, thus for all columns in that bucket (IOW it's not a leaf!)
                processSeries(rowBuilder, searchResult, queryContext, pivot, new ArrayDeque<>(), aggregation, true, "row-inner");
                resultBuilder.addRow(rowBuilder.source("non-leaf").build());
            }

        }
    }

    private void processColumns(PivotResult.Row.Builder rowBuilder,
                                SearchResult searchResult,
                                ESGeneratedQueryContext queryContext,
                                Pivot pivot,
                                List<BucketSpec> remainingColumns,
                                ArrayDeque<String> columnKeys,
                                MetricAggregation aggregation) {
        if (remainingColumns.isEmpty()) {
            // this is the leaf cell of the pivot table, simply add all the series for the complete column key array
            // in the rollup: false case, this is the only set of series that is going to be added to the result
            // if we simply don't have any column groups, then don't bother adding the series, this is the special case that
            // only row grouping was requested, and the rollup for rows is automatically added anyway. otherwise we'll end up
            // with duplicate data entries
            if (!columnKeys.isEmpty()) {
                processSeries(rowBuilder, searchResult, queryContext, pivot, columnKeys, aggregation, false, "col-leaf");
            }
        } else {
            // for a non-leaf column group, we need to recurse further into the aggregation tree
            // and if rollup was requested we'll add intermediate series according to the column keys
            final BucketSpec currentBucket = remainingColumns.get(0);

            // this handler should never be missing, because we used it above to generate the query
            // if it is missing for some weird reason, it's ok to fail hard here
            final ESPivotBucketSpecHandler<? extends PivotSpec, ? extends Aggregation> handler = bucketHandlers.get(currentBucket.type());
            final Aggregation aggregationResult = handler.extractAggregationFromResult(pivot, currentBucket, aggregation, queryContext);
            final Stream<ESPivotBucketSpecHandler.Bucket> bucketStream = handler.handleResult(pivot, currentBucket, searchResult, aggregationResult, this, queryContext);

            // for each bucket, recurse and eventually collect all the column keys. once we reach a leaf, we'll end up in the other if branch above
            bucketStream.forEach(bucket -> {
                // push the bucket's key and use its aggregation as the new source for sub-aggregations
                columnKeys.addLast(bucket.key());
                processColumns(rowBuilder, searchResult, queryContext, pivot, tail(remainingColumns), columnKeys, bucket.aggregation());
                columnKeys.removeLast();
            });
            // also add the series for the base column key if the client wants rollups, the complete column key is processed in the leaf branch
            // don't add the empty column key rollup, because that's not the correct bucket here, it's being done in the row-leaf code
            if (pivot.rollup() && !columnKeys.isEmpty()) {
                // columnKeys is not empty, because this is a rollup per column in a row
                processSeries(rowBuilder, searchResult, queryContext, pivot, columnKeys, aggregation, true, "col-inner");
            }

        }
    }

    private void processSeries(PivotResult.Row.Builder rowBuilder,
                               SearchResult searchResult,
                               ESGeneratedQueryContext queryContext,
                               Pivot pivot,
                               ArrayDeque<String> columnKeys,
                               MetricAggregation aggregation,
                               boolean rollup,
                               String source) {
        pivot.series().forEach(seriesSpec -> {
            final ESPivotSeriesSpecHandler<? extends SeriesSpec, ? extends Aggregation> seriesHandler = seriesHandlers.get(seriesSpec.type());
            final Aggregation series = seriesHandler.extractAggregationFromResult(pivot, seriesSpec, aggregation, queryContext);
            seriesHandler.handleResult(pivot, seriesSpec, searchResult, series, this, queryContext)
                    .map(value -> {
                        columnKeys.addLast(value.id());
                        final PivotResult.Value v = PivotResult.Value.create(columnKeys, value.value(), rollup, source);
                        columnKeys.removeLast();
                        return v;
                    })
                    .forEach(rowBuilder::addValue);
        });
    }

    private static <T> List<T> tail(List<T> list) {
        Preconditions.checkArgument(!list.isEmpty(), "List must not be empty!");
        return list.subList(1, list.size());
    }

    /**
     * This solely exists to hide the nasty type signature of the aggregation type map from the rest of the code.
     * It's just ugly and in the way.
     */
    public static class AggTypes {
        final IdentityHashMap<PivotSpec, Tuple2<String, Class<? extends Aggregation>>> aggTypeMap = new IdentityHashMap<>();

        public void record(PivotSpec pivotSpec, String name, Class<? extends Aggregation> aggClass) {
            aggTypeMap.put(pivotSpec, Tuple.tuple(name, aggClass));
        }

        public Aggregation getSubAggregation(PivotSpec pivotSpec, MetricAggregation currentAggregationOrBucket) {
            final Tuple2<String, Class<? extends Aggregation>> tuple2 = getTypes(pivotSpec);
            return currentAggregationOrBucket.getAggregation(tuple2.v1, tuple2.v2);
        }

        public Tuple2<String, Class<? extends Aggregation>> getTypes(PivotSpec pivotSpec) {
            return aggTypeMap.get(pivotSpec);
        }
    }
}

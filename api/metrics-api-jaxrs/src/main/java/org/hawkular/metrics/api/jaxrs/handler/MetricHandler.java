/*
 * Copyright 2014-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.api.jaxrs.handler;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import static org.hawkular.metrics.api.jaxrs.filter.TenantFilter.TENANT_HEADER_NAME;
import static org.hawkular.metrics.api.jaxrs.util.ApiUtils.badRequest;
import static org.hawkular.metrics.api.jaxrs.util.ApiUtils.emptyPayload;
import static org.hawkular.metrics.api.jaxrs.util.ApiUtils.serverError;
import static org.hawkular.metrics.model.MetricType.AVAILABILITY;
import static org.hawkular.metrics.model.MetricType.COUNTER;
import static org.hawkular.metrics.model.MetricType.COUNTER_RATE;
import static org.hawkular.metrics.model.MetricType.GAUGE;
import static org.hawkular.metrics.model.MetricType.GAUGE_RATE;
import static org.hawkular.metrics.model.MetricType.STRING;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.hawkular.metrics.api.jaxrs.StatsQueryRequest;
import org.hawkular.metrics.api.jaxrs.handler.observer.MetricCreatedObserver;
import org.hawkular.metrics.api.jaxrs.handler.transformer.MinMaxTimestampTransformer;
import org.hawkular.metrics.api.jaxrs.param.DurationConverter;
import org.hawkular.metrics.api.jaxrs.param.PercentilesConverter;
import org.hawkular.metrics.api.jaxrs.param.TagsConverter;
import org.hawkular.metrics.api.jaxrs.util.ApiUtils;
import org.hawkular.metrics.api.jaxrs.util.MetricTypeTextConverter;
import org.hawkular.metrics.core.service.Functions;
import org.hawkular.metrics.core.service.MetricsService;
import org.hawkular.metrics.model.ApiError;
import org.hawkular.metrics.model.AvailabilityBucketPoint;
import org.hawkular.metrics.model.AvailabilityType;
import org.hawkular.metrics.model.BucketPoint;
import org.hawkular.metrics.model.Metric;
import org.hawkular.metrics.model.MetricId;
import org.hawkular.metrics.model.MetricType;
import org.hawkular.metrics.model.MixedMetricsRequest;
import org.hawkular.metrics.model.NumericBucketPoint;
import org.hawkular.metrics.model.Percentile;
import org.hawkular.metrics.model.param.BucketConfig;
import org.hawkular.metrics.model.param.Duration;
import org.hawkular.metrics.model.param.Tags;
import org.hawkular.metrics.model.param.TimeRange;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ObjectArrays;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import rx.Observable;
import rx.functions.Func1;


/**
 * Interface to deal with metrics
 *
 * @author Heiko W. Rupp
 */
@Path("/metrics")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Api(tags = "Metric")
@ApplicationScoped
public class MetricHandler {
    @Inject
    private MetricsService metricsService;

    @Context
    private HttpHeaders httpHeaders;

    private String getTenant() {
        return httpHeaders.getRequestHeaders().getFirst(TENANT_HEADER_NAME);
    }

    @POST
    @Path("/")
    @ApiOperation(value = "Create metric.", notes = "Clients are not required to explicitly create "
            + "a metric before storing data. Doing so however allows clients to prevent naming collisions and to "
            + "specify tags and data retention.")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Metric created successfully"),
            @ApiResponse(code = 400, message = "Missing or invalid payload", response = ApiError.class),
            @ApiResponse(code = 409, message = "Metric with given id already exists",
                    response = ApiError.class),
            @ApiResponse(code = 500, message = "Metric creation failed due to an unexpected error",
                    response = ApiError.class)
    })
    public <T> void createMetric(
            @Suspended final AsyncResponse asyncResponse,
            @ApiParam(required = true) Metric<T> metric,
            @ApiParam(value = "Overwrite previously created metric if it exists. Defaults to false.",
                    required = false) @DefaultValue("false") @QueryParam("overwrite") Boolean overwrite,
            @Context UriInfo uriInfo
    ) {
        if (metric.getType() == null || !metric.getType().isUserType()) {
            asyncResponse.resume(badRequest(new ApiError("Metric type is invalid")));
        }
        MetricId<T> id = new MetricId<>(getTenant(), metric.getMetricId().getType(), metric.getId());
        metric = new Metric<>(id, metric.getTags(), metric.getDataRetention());
        URI location = uriInfo.getBaseUriBuilder().path("/{type}/{id}").build(MetricTypeTextConverter.getLongForm(id
                .getType()), id.getName());
        metricsService.createMetric(metric, overwrite).subscribe(new MetricCreatedObserver(asyncResponse, location));
    }

    @GET
    @Path("/tags/{tags}")
    @ApiOperation(value = "Retrieve metrics' tag values", response = Map.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Tags successfully retrieved."),
            @ApiResponse(code = 204, message = "No matching tags were found"),
            @ApiResponse(code = 500, message = "Unexpected error occurred while fetching tags.",
                    response = ApiError.class)
    })
    public <T> void getTags(@Suspended final AsyncResponse asyncResponse,
                            @ApiParam(value = "Queried metric type", allowableValues = "gauge, availability, counter")
                            @QueryParam("type") MetricType<T> metricType,
                            @ApiParam("Tag query") @PathParam("tags") Tags tags) {
        metricsService.getTagValues(getTenant(), metricType, tags.getTags())
                .map(ApiUtils::mapToResponse)
                .subscribe(asyncResponse::resume, t -> asyncResponse.resume(ApiUtils.serverError(t)));
    }

    @GET
    @Path("/")
    @ApiOperation(value = "Find tenant's metric definitions.", notes = "Does not include any metric values. ",
                    response = Metric.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successfully retrieved at least one metric definition."),
            @ApiResponse(code = 204, message = "No metrics found."),
            @ApiResponse(code = 400, message = "Invalid type parameter type.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Failed to retrieve metrics due to unexpected error.",
                    response = ApiError.class)
    })
    public <T> void findMetrics(
            @Suspended
            AsyncResponse asyncResponse,
            @ApiParam(value = "Queried metric type", required = false, allowableValues = "gauge, availability, counter")
            @QueryParam("type") MetricType<T> metricType,
            @ApiParam(value = "List of tags filters", required = false) @QueryParam("tags") Tags tags,
            @ApiParam(value = "Regexp to match metricId, requires tags filtering", required = false) @QueryParam("id")
            String id
    ) {
        if (metricType != null && !metricType.isUserType()) {
            asyncResponse.resume(badRequest(new ApiError("Incorrect type param " + metricType.toString())));
            return;
        }

        @SuppressWarnings("unchecked")
        Func1<Metric<T>, Boolean>[] metricFuncs = ObjectArrays.newArray(Func1.class, 0);
        if(!Strings.isNullOrEmpty(id)) {
            metricFuncs = ObjectArrays.concat(metricFuncs, metricsService.idFilter(id));
        }

        Observable<Metric<T>> metricObservable = (tags == null)
                ? metricsService.findMetrics(getTenant(), metricType)
                : metricsService.findMetricsWithFilters(getTenant(), metricType, tags.getTags(), metricFuncs);

        metricObservable
                .compose(new MinMaxTimestampTransformer<>(metricsService))
                .toList()
                .map(ApiUtils::collectionToResponse)
                .subscribe(asyncResponse::resume, t -> {
                    if (t instanceof PatternSyntaxException) {
                        asyncResponse.resume(badRequest(t));
                    } else {
                        asyncResponse.resume(serverError(t));
                    }
                });
    }

    @Deprecated
    @POST
    @Path("/data")
    @ApiOperation(value = "Deprecated. Please use /raw endpoint.")
    public void deprecatedAddMetricsData(
            @Suspended final AsyncResponse asyncResponse,
            @ApiParam(value = "List of metrics", required = true) MixedMetricsRequest metricsRequest) {
        addMetricsData(asyncResponse, metricsRequest);
    }

    @POST
    @Path("/raw")
    @ApiOperation(value = "Add data points for multiple metrics in a single call.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Adding data points succeeded."),
            @ApiResponse(code = 400, message = "Missing or invalid payload.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Unexpected error happened while storing the data",
                    response = ApiError.class)
    })
    public void addMetricsData(
            @Suspended final AsyncResponse asyncResponse,
            @ApiParam(value = "List of metrics", required = true) MixedMetricsRequest metricsRequest
    ) {
        if (metricsRequest.isEmpty()) {
            asyncResponse.resume(emptyPayload());
            return;
        }

        Observable<Metric<Double>> gauges =
                Functions.metricToObservable(getTenant(), metricsRequest.getGauges(), GAUGE);
        Observable<Metric<AvailabilityType>> availabilities = Functions.metricToObservable(getTenant(),
                metricsRequest.getAvailabilities(), AVAILABILITY);
        Observable<Metric<Long>> counters = Functions.metricToObservable(getTenant(), metricsRequest.getCounters(),
                COUNTER);
        Observable<Metric<String>> strings = Functions.metricToObservable(getTenant(), metricsRequest.getStrings(),
                STRING);

        metricsService.addDataPoints(GAUGE, gauges)
                .mergeWith(metricsService.addDataPoints(AVAILABILITY, availabilities))
                .mergeWith(metricsService.addDataPoints(COUNTER, counters))
                .mergeWith(metricsService.addDataPoints(STRING, strings))
                .subscribe(
                        aVoid -> {
                        },
                        t -> asyncResponse.resume(serverError(t)),
                        () -> asyncResponse.resume(Response.ok().build())
                );
    }

    @POST
    @Path("/stats/query")
    @SuppressWarnings("unchecked")
    public void findStats(@Suspended AsyncResponse asyncResponse, StatsQueryRequest query) {
        if (isMetricIdsEmpty(query) && query.getTags() == null) {
            asyncResponse.resume(badRequest(new ApiError("Either the metrics or the tags property must be set")));
        }

        if (query.getBuckets() == null && query.getBucketDuration() == null) {
            asyncResponse.resume(badRequest(new ApiError("Either the buckets or bucketDuration property must be set")));
            return;
        }

        Duration duration;
        if (query.getBucketDuration() == null) {
            duration = null;
        } else {
            duration = new DurationConverter().fromString(query.getBucketDuration());
        }
        TimeRange timeRange = new TimeRange(query.getStart(), query.getEnd());
        BucketConfig bucketsConfig = new BucketConfig(query.getBuckets(), duration, timeRange);

        List<Percentile> percentiles;
        if (query.getPercentiles() == null) {
            percentiles = emptyList();
        } else {
            percentiles = new PercentilesConverter().fromString(query.getPercentiles()).getPercentiles();
        }

        List<MetricType<?>> types = emptyList();
        if (query.getTypes() != null) {
            types = query.getTypes().stream().map(MetricType::fromTextCode).collect(Collectors.toList());
        }

        Observable<Map<String, List<? extends BucketPoint>>> gaugeStats;
        Observable<Map<String, List<? extends BucketPoint>>> counterStats;
        Observable<Map<String, List<? extends BucketPoint>>> availabilityStats;
        Observable<Map<String, List<? extends BucketPoint>>> gaugeRateStats = Observable.just(emptyMap());
        Observable<Map<String, List<? extends BucketPoint>>> counterRateStats = Observable.just(emptyMap());


        if (!query.getMetrics().isEmpty() && (query.getMetrics().containsKey("gauge") ||
                query.getMetrics().containsKey("counter") || query.getMetrics().containsKey("availability"))) {

            if (query.getMetrics().get("gauge") != null && !query.getMetrics().get("gauge").isEmpty()) {
                if (types.isEmpty()) {
                    gaugeStats = getGaugeStats(query, bucketsConfig, percentiles);
                } else if (types.contains(GAUGE_RATE)) {
                    if (types.contains(GAUGE)) {
                        gaugeStats = getGaugeStats(query, bucketsConfig, percentiles);
                        gaugeRateStats = getRateStats(query, GAUGE, bucketsConfig, percentiles);
                    } else {
                        gaugeStats = Observable.just(emptyMap());
                        gaugeRateStats = getRateStats(query, GAUGE, bucketsConfig, percentiles);
                    }
                } else {
                    gaugeStats = getGaugeStats(query, bucketsConfig, percentiles);
                }
            } else {
                gaugeStats = Observable.just(emptyMap());
            }

            if (query.getMetrics().get("counter") != null && !query.getMetrics().get("counter").isEmpty()) {
                if (types.isEmpty()) {
                    counterStats = getCounterStats(query, timeRange, bucketsConfig, percentiles);
                } else if (types.contains(COUNTER_RATE)) {
                    if (types.contains(COUNTER)) {
                        counterStats = getCounterStats(query, timeRange, bucketsConfig, percentiles);
                        counterRateStats = getRateStats(query, COUNTER, bucketsConfig, percentiles);

                    } else {
                        counterStats = Observable.just(emptyMap());
                        counterRateStats = getRateStats(query, COUNTER, bucketsConfig, percentiles);
                    }
                } else {
                    counterStats = getCounterStats(query, timeRange, bucketsConfig, percentiles);
                }
            } else {
                counterStats = Observable.just(emptyMap());
            }

            if (query.getMetrics().get("availability") != null && !query.getMetrics().get("availability").isEmpty()) {
                availabilityStats = Observable.from(query.getMetrics().get("availability"))
                        .flatMap(id -> metricsService.findAvailabilityStats(new MetricId<>(getTenant(), AVAILABILITY,
                                id), timeRange.getStart(), timeRange.getEnd(), bucketsConfig.getBuckets())
                                .map(bucketPoints -> new NamedBucketPoints(id, bucketPoints)))
                        .collect(HashMap::new, (statsMap, namedBucketPoints) -> statsMap.put(namedBucketPoints.id,
                                namedBucketPoints.bucketPoints));
            } else {
                availabilityStats = Observable.just(emptyMap());
            }
        } else {
            Func1[] filters = new Func1[0];
            Tags tags = new TagsConverter().fromString(query.getTags());

            Observable<Metric<Double>> gauges = metricsService.findMetricsWithFilters(getTenant(), GAUGE,
                    tags.getTags(), filters);
            Observable<Metric<Long>> counters = metricsService.findMetricsWithFilters(getTenant(), COUNTER,
                    tags.getTags(), filters);
            Observable<Metric<AvailabilityType>> availabilities = metricsService.findMetricsWithFilters(getTenant(),
                    AVAILABILITY, tags.getTags(), filters);
            gaugeStats = gauges.flatMap(gauge ->
                    metricsService.findGaugeStats(gauge.getMetricId(), bucketsConfig, percentiles)
                            .map(bucketPoints -> new NamedBucketPoints(gauge.getMetricId().getName(), bucketPoints)))
                    .collect(HashMap::new, (statsMap, namedBucketPoints) ->
                            statsMap.put(namedBucketPoints.id, namedBucketPoints.bucketPoints));
            counterStats = counters.flatMap(counter ->
                    metricsService.findCounterStats(counter.getMetricId(), timeRange.getStart(), timeRange.getEnd(),
                            bucketsConfig.getBuckets(), percentiles)
                            .map(bucketPoints -> new NamedBucketPoints<>(counter.getMetricId().getName(), bucketPoints)))
                    .collect(HashMap::new, (statsMap, namedBucketPoints) ->
                            statsMap.put(namedBucketPoints.id, namedBucketPoints.bucketPoints));
            availabilityStats = availabilities.flatMap(availability -> metricsService.findAvailabilityStats(
                    availability.getMetricId(), timeRange.getStart(), timeRange.getEnd(), bucketsConfig.getBuckets())
                            .map(bucketPoints -> new NamedBucketPoints<>(availability.getMetricId().getName(),
                                    bucketPoints)))
                    .collect(HashMap::new, (statsMap, namedBucketPoints) ->
                            statsMap.put(namedBucketPoints.id, namedBucketPoints.bucketPoints));
        }

        Observable.zip(gaugeStats, counterStats, availabilityStats, gaugeRateStats, counterRateStats,
                (gaugeMap, counterMap, availabiltyMap, gaugeRateMap, counterRateMap) -> {
                    Map<String, Map<String, List<? extends BucketPoint>>> stats = new HashMap<>();
                    if (!gaugeMap.isEmpty()) {
                        stats.put("gauge", gaugeMap);
                    }
                    if (!counterMap.isEmpty()) {
                        stats.put("counter", counterMap);
                    }
                    if (!availabiltyMap.isEmpty()) {
                        stats.put("availability", availabiltyMap);
                    }
                    if (!gaugeRateMap.isEmpty()) {
                        stats.put("gauge_rate", gaugeRateMap);
                    }
                    if (!counterRateMap.isEmpty()) {
                        stats.put("counter_rate", counterRateMap);
                    }
                    return stats;
                })
                .first()
                .map(ApiUtils::mapToResponse)
                .subscribe(asyncResponse::resume, t -> asyncResponse.resume(ApiUtils.error(t)));
    }

    @SuppressWarnings("unchecked")
    private Observable<Map<String, List<? extends BucketPoint>>> getGaugeStats(StatsQueryRequest query,
            BucketConfig bucketsConfig, List<Percentile> percentiles) {
        Observable<Map<String, List<? extends BucketPoint>>> gaugeStats;
        gaugeStats = Observable.from(query.getMetrics().get("gauge"))
                .flatMap(id -> metricsService.findGaugeStats(new MetricId<>(getTenant(), GAUGE, id),
                        bucketsConfig, percentiles)
                        .map(bucketPoints -> new NamedBucketPoints(id, bucketPoints)))
                .collect(HashMap::new, (statsMap, namedBucketPoints) ->
                        statsMap.put(namedBucketPoints.id, namedBucketPoints.bucketPoints));
        return gaugeStats;
    }

    @SuppressWarnings("unchecked")
    private Observable<Map<String, List<? extends BucketPoint>>> getCounterStats(StatsQueryRequest query,
            TimeRange timeRange, BucketConfig bucketsConfig, List<Percentile> percentiles) {
        Observable<Map<String, List<? extends BucketPoint>>> counterStats;
        counterStats = Observable.from(query.getMetrics().get("counter"))
                .flatMap(id -> metricsService.findCounterStats(new MetricId<>(getTenant(), COUNTER, id),
                        timeRange.getStart(), timeRange.getEnd(), bucketsConfig.getBuckets(), percentiles)
                        .map(bucketPoints -> new NamedBucketPoints(id, bucketPoints)))
                .collect(HashMap::new, (statsMap, namedBucketPoints) -> statsMap.put(namedBucketPoints.id,
                        namedBucketPoints.bucketPoints));
        return counterStats;
    }

    @SuppressWarnings("unchecked")
    private Observable<Map<String, List<? extends BucketPoint>>> getRateStats(StatsQueryRequest query,
            MetricType<? extends Number> type, BucketConfig bucketConfig, List<Percentile> percentiles) {
        return Observable.from(query.getMetrics().get(type.getText()))
                .flatMap(id -> metricsService.findRateStats(new MetricId<>(getTenant(), type, id),
                        bucketConfig.getTimeRange().getStart(), bucketConfig.getTimeRange().getEnd(),
                        bucketConfig.getBuckets(), percentiles)
                        .map(bucketPoints -> new NamedBucketPoints(id, bucketPoints)))
                .collect(HashMap::new, (statsMap, namedBucketPoints) -> statsMap.put(namedBucketPoints.id,
                        namedBucketPoints.bucketPoints));
    }

    private boolean isMetricIdsEmpty(StatsQueryRequest query) {
        if (query.getMetrics().isEmpty()) {
            return true;
        }
        return query.getMetrics().getOrDefault("gauge", emptyList()).isEmpty() &&
                query.getMetrics().getOrDefault("counter", emptyList()).isEmpty() &&
                query.getMetrics().getOrDefault("availability", emptyList()).isEmpty();
    }

    private class NamedBucketPoints<T extends BucketPoint> {
        public String id;
        public List<T> bucketPoints;

        public NamedBucketPoints(String id, List<T> bucketPoints) {
            this.id = id;
            this.bucketPoints = bucketPoints;
        }
    }

}

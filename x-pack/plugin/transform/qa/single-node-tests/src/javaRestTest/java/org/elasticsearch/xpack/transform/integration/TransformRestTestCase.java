/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.integration;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.logging.log4j.Level;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.core.transform.TransformField;
import org.elasticsearch.xpack.core.transform.transforms.persistence.TransformInternalIndexConstants;
import org.junit.After;
import org.junit.AfterClass;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public abstract class TransformRestTestCase extends ESRestTestCase {

    protected static final String TEST_PASSWORD = "x-pack-test-password";
    private static final String SECONDARY_AUTH_KEY = "es-secondary-authorization";
    protected static final SecureString TEST_PASSWORD_SECURE_STRING = new SecureString(TEST_PASSWORD.toCharArray());
    private static final String BASIC_AUTH_VALUE_SUPER_USER = basicAuthHeaderValue("x_pack_rest_user", TEST_PASSWORD_SECURE_STRING);

    protected static final String REVIEWS_INDEX_NAME = "reviews";
    protected static final String REVIEWS_DATE_NANO_INDEX_NAME = "reviews_nano";

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(ThreadContext.PREFIX + ".Authorization", BASIC_AUTH_VALUE_SUPER_USER).build();
    }

    protected void createReviewsIndex(
        String indexName,
        int numDocs,
        int numUsers,
        String dateType,
        boolean isDataStream,
        int userWithMissingBuckets,
        String missingBucketField
    ) throws IOException {
        putReviewsIndex(indexName, dateType, isDataStream);

        int[] distributionTable = { 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4, 4, 4, 3, 3, 2, 1, 1, 1 };
        // create index
        final StringBuilder bulk = new StringBuilder();
        int day = 10;
        int hour = 10;
        int min = 10;
        for (int i = 0; i < numDocs; i++) {
            bulk.append(Strings.format("""
                {"create":{"_index":"%s"}}
                """, indexName));
            long user = Math.round(Math.pow(i * 31 % 1000, distributionTable[i % distributionTable.length]) % numUsers);
            int stars = distributionTable[(i * 33) % distributionTable.length];
            long business = Math.round(Math.pow(user * stars, distributionTable[i % distributionTable.length]) % 13);
            long affiliate = Math.round(Math.pow(user * stars, distributionTable[i % distributionTable.length]) % 11);

            if (i % 12 == 0) {
                hour = 10 + (i % 13);
            }
            if (i % 5 == 0) {
                min = 10 + (i % 49);
            }
            int sec = 10 + (i % 49);
            String location = (((user + 10) % 180) - 90) + "," + (((user + 15) % 360) - 180);

            String date_string = "2017-01-" + day + "T" + hour + ":" + min + ":" + sec;
            if (dateType.equals("date_nanos")) {
                String randomNanos = "," + randomIntBetween(100000000, 999999999);
                date_string += randomNanos;
            }
            date_string += "Z";

            bulk.append("{");
            if ((user == userWithMissingBuckets && missingBucketField.equals("user_id")) == false) {
                bulk.append("\"user_id\":\"").append("user_").append(user).append("\",");
            }
            if ((user == userWithMissingBuckets && missingBucketField.equals("business_id")) == false) {
                bulk.append("\"business_id\":\"").append("business_").append(business).append("\",");
            }
            if ((user == userWithMissingBuckets && missingBucketField.equals("stars")) == false) {
                bulk.append("\"stars\":").append(stars).append(",");
            }
            if ((user == userWithMissingBuckets && missingBucketField.equals("stars_stats")) == false) {
                bulk.append("\"stars_stats\": { ")
                    .append("\"min\": ")
                    .append(stars - 1)
                    .append(",")
                    .append("\"max\": ")
                    .append(stars + 1)
                    .append(",")
                    .append("\"sum\": ")
                    .append(10 * stars)
                    .append("},");
            }
            if ((user == userWithMissingBuckets && missingBucketField.equals("location")) == false) {
                bulk.append("\"location\":\"").append(location).append("\",");
            }
            if ((user == userWithMissingBuckets && missingBucketField.equals("timestamp")) == false) {
                bulk.append("\"timestamp\":\"").append(date_string).append("\",");
            }
            if ((user == userWithMissingBuckets && missingBucketField.equals("affiliate_id")) == false) {
                bulk.append("\"affiliate_id\":\"").append("affiliate_").append(affiliate).append("\",");
            }

            // always add @timestamp to avoid complicated logic regarding ','
            bulk.append("\"@timestamp\":\"").append(date_string).append("\"");
            bulk.append("}\n");

            if (i % 50 == 0) {
                bulk.append("\r\n");
                doBulk(bulk.toString(), true);
                // clear the builder
                bulk.setLength(0);
                day += 1;
            }
        }

        bulk.append("\r\n");
        doBulk(bulk.toString(), true);
    }

    @SuppressWarnings("unchecked")
    protected void doBulk(String bulkDocuments, boolean refresh) throws IOException {
        Request bulkRequest = new Request("POST", "/_bulk");
        if (refresh) {
            bulkRequest.addParameter("refresh", "true");
        }
        bulkRequest.setJsonEntity(bulkDocuments);
        bulkRequest.setOptions(RequestOptions.DEFAULT);
        Response bulkResponse = client().performRequest(bulkRequest);
        assertOK(bulkResponse);
        var bulkMap = entityAsMap(bulkResponse);
        var hasErrors = (boolean) bulkMap.get("errors");
        if (hasErrors) {
            var items = (List<Map<String, Object>>) bulkMap.get("items");
            fail("Bulk item failures: " + items.toString());
        }
    }

    protected void putReviewsIndex(String indexName, String dateType, boolean isDataStream) throws IOException {
        // create mapping
        try (XContentBuilder builder = jsonBuilder()) {
            builder.startObject();
            {
                builder.startObject("mappings").startObject("properties");
                builder.startObject("@timestamp").field("type", dateType);
                if (dateType.equals("date_nanos")) {
                    builder.field("format", "strict_date_optional_time_nanos");
                }
                builder.endObject();
                builder.startObject("timestamp").field("type", dateType);
                if (dateType.equals("date_nanos")) {
                    builder.field("format", "strict_date_optional_time_nanos");
                }
                builder.endObject()
                    .startObject("user_id")
                    .field("type", "keyword")
                    .endObject()
                    .startObject("business_id")
                    .field("type", "keyword")
                    .endObject()
                    .startObject("stars")
                    .field("type", randomFrom("integer", "long")) // gh#64347 unsigned_long disabled
                    .endObject()
                    .startObject("stars_stats")
                    .field("type", "aggregate_metric_double")
                    .field("metrics", List.of("min", "max", "sum"))
                    .field("default_metric", "max")
                    .endObject()
                    .startObject("location")
                    .field("type", "geo_point")
                    .endObject()
                    .startObject("affiliate_id")
                    .field("type", "keyword")
                    .endObject()
                    .endObject()
                    .endObject();
            }
            builder.endObject();
            if (isDataStream) {
                Request createCompositeTemplate = new Request("PUT", "_index_template/" + indexName + "_template");
                createCompositeTemplate.setJsonEntity(Strings.format("""
                    {
                      "index_patterns": [ "%s" ],
                      "data_stream": {
                      },
                      "template":
                      %s
                    }""", indexName, Strings.toString(builder)));
                client().performRequest(createCompositeTemplate);
                client().performRequest(new Request("PUT", "_data_stream/" + indexName));
            } else {
                final StringEntity entity = new StringEntity(Strings.toString(builder), ContentType.APPLICATION_JSON);
                Request req = new Request("PUT", indexName);
                req.setEntity(entity);
                client().performRequest(req);
            }
        }
    }

    /**
     * Create a simple dataset for testing with reviewers, ratings and businesses
     */
    protected void createReviewsIndex() throws IOException {
        createReviewsIndex(REVIEWS_INDEX_NAME);
    }

    protected void createReviewsIndex(String indexName) throws IOException {
        createReviewsIndex(indexName, 1000, 27, "date", false, 5, "affiliate_id");
    }

    protected void createPivotReviewsTransform(String transformId, String transformIndex, String query) throws IOException {
        createPivotReviewsTransform(transformId, transformIndex, query, null);
    }

    protected void createPivotReviewsTransform(String transformId, String transformIndex, String query, String pipeline)
        throws IOException {
        createPivotReviewsTransform(transformId, transformIndex, query, pipeline, null);
    }

    protected void createReviewsIndexNano() throws IOException {
        createReviewsIndex(REVIEWS_DATE_NANO_INDEX_NAME, 1000, 27, "date_nanos", false, -1, null);
    }

    protected void createContinuousPivotReviewsTransform(String transformId, String transformIndex, String authHeader) throws IOException {

        // Set frequency high for testing
        String config = Strings.format("""
            {
              "dest": {
                "index": "%s"
              },
              "source": {
                "index": "%s"
              },
              "sync": {
                "time": {
                  "field": "timestamp",
                  "delay": "15m"
                }
              },
              "frequency": "1s",
              "pivot": {
                "group_by": {
                  "reviewer": {
                    "terms": {
                      "field": "user_id"
                    }
                  }
                },
                "aggregations": {
                  "avg_rating": {
                    "avg": {
                      "field": "stars"
                    }
                  }
                }
              }
            }""", transformIndex, REVIEWS_INDEX_NAME);

        createReviewsTransform(transformId, authHeader, null, config);
    }

    protected void createPivotReviewsTransform(
        String transformId,
        String transformIndex,
        String query,
        String pipeline,
        String authHeader,
        String sourceIndex
    ) throws IOException {
        createPivotReviewsTransform(transformId, transformIndex, query, pipeline, authHeader, null, sourceIndex);
    }

    protected void createPivotReviewsTransform(
        String transformId,
        String transformIndex,
        String query,
        String pipeline,
        String authHeader,
        String secondaryAuthHeader,
        String sourceIndex
    ) throws IOException {
        String config = "{";

        if (pipeline != null) {
            config += Strings.format("""
                "dest": {"index":"%s", "pipeline":"%s"},""", transformIndex, pipeline);
        } else {
            config += Strings.format("""
                "dest": {"index":"%s"},""", transformIndex);
        }

        if (query != null) {
            config += Strings.format("""
                "source": {"index":"%s", "query":{%s}},""", sourceIndex, query);
        } else {
            config += Strings.format("""
                "source": {"index":"%s"},""", sourceIndex);
        }

        config += """
              "pivot": {
                "group_by": {
                  "reviewer": {
                    "terms": {
                      "field": "user_id"
                    }
                  }
                },
                "aggregations": {
                  "avg_rating": {
                    "avg": {
                      "field": "stars"
                    }
                  },
                  "affiliate_missing": {
                    "missing": {
                      "field": "affiliate_id"
                    }
                  },
                  "stats": {
                    "stats": {
                      "field": "stars"
                    }
                  }
                }
              },
              "frequency": "1s"
            }""";

        createReviewsTransform(transformId, authHeader, secondaryAuthHeader, config);
    }

    protected void createLatestReviewsTransform(String transformId, String transformIndex) throws IOException {
        String config = Strings.format("""
            {
              "dest": {
                "index": "%s"
              },
              "source": {
                "index": "%s"
              },
              "latest": {
                "unique_key": [
                  "user_id"
                ],
                "sort": "@timestamp"
              },
              "frequency": "1s"
            }""", transformIndex, REVIEWS_INDEX_NAME);

        createReviewsTransform(transformId, null, null, config);
    }

    private void createReviewsTransform(String transformId, String authHeader, String secondaryAuthHeader, String config)
        throws IOException {
        final Request createTransformRequest = createRequestWithSecondaryAuth(
            "PUT",
            getTransformEndpoint() + transformId,
            authHeader,
            secondaryAuthHeader
        );
        createTransformRequest.setJsonEntity(config);

        Map<String, Object> createTransformResponse = entityAsMap(client().performRequest(createTransformRequest));
        assertThat(createTransformResponse.get("acknowledged"), equalTo(Boolean.TRUE));
    }

    protected void createPivotReviewsTransform(String transformId, String transformIndex, String query, String pipeline, String authHeader)
        throws IOException {
        createPivotReviewsTransform(transformId, transformIndex, query, pipeline, authHeader, null, REVIEWS_INDEX_NAME);
    }

    protected void startTransform(String transformId) throws IOException {
        startTransform(transformId, null);
    }

    protected void startTransform(String transformId, String authHeader, String... warnings) throws IOException {
        // start the transform
        startTransform(transformId, authHeader, null, warnings);
    }

    protected void startTransform(String transformId, String authHeader, String secondaryAuthHeader, String... warnings)
        throws IOException {
        // start the transform
        final Request startTransformRequest = createRequestWithSecondaryAuth(
            "POST",
            getTransformEndpoint() + transformId + "/_start",
            authHeader,
            secondaryAuthHeader
        );
        if (warnings.length > 0) {
            startTransformRequest.setOptions(expectWarnings(warnings));
        }
        Map<String, Object> startTransformResponse = entityAsMap(client().performRequest(startTransformRequest));
        assertThat(startTransformResponse.get("acknowledged"), equalTo(Boolean.TRUE));
    }

    protected void stopTransform(String transformId, boolean force) throws Exception {
        stopTransform(transformId, force, false);
    }

    protected void stopTransform(String transformId, boolean force, boolean waitForCheckpoint) throws Exception {
        stopTransform(transformId, null, force, false);
    }

    protected void stopTransform(String transformId, String authHeader, boolean force, boolean waitForCheckpoint) throws Exception {
        final Request stopTransformRequest = createRequestWithAuth("POST", getTransformEndpoint() + transformId + "/_stop", authHeader);
        stopTransformRequest.addParameter(TransformField.FORCE.getPreferredName(), Boolean.toString(force));
        stopTransformRequest.addParameter(TransformField.WAIT_FOR_COMPLETION.getPreferredName(), Boolean.toString(true));
        stopTransformRequest.addParameter(TransformField.WAIT_FOR_CHECKPOINT.getPreferredName(), Boolean.toString(waitForCheckpoint));
        Map<String, Object> stopTransformResponse = entityAsMap(client().performRequest(stopTransformRequest));
        assertThat(stopTransformResponse.get("acknowledged"), equalTo(Boolean.TRUE));
    }

    protected void startAndWaitForTransform(String transformId, String transformIndex) throws Exception {
        startAndWaitForTransform(transformId, transformIndex, null);
    }

    protected void startAndWaitForTransform(String transformId, String transformIndex, String authHeader) throws Exception {
        startAndWaitForTransform(transformId, transformIndex, authHeader, new String[0]);
    }

    protected void startAndWaitForTransform(String transformId, String transformIndex, String authHeader, String... warnings)
        throws Exception {
        startAndWaitForTransform(transformId, transformIndex, authHeader, null, warnings);
    }

    protected void startAndWaitForTransform(
        String transformId,
        String transformIndex,
        String authHeader,
        String secondaryAuthHeader,
        String... warnings
    ) throws Exception {
        // start the transform
        startTransform(transformId, authHeader, secondaryAuthHeader, warnings);
        assertTrue(indexExists(transformIndex));
        // wait until the transform has been created and all data is available
        waitForTransformCheckpoint(transformId);

        waitForTransformStopped(transformId);
        refreshIndex(transformIndex);
    }

    protected void startAndWaitForContinuousTransform(String transformId, String transformIndex, String authHeader) throws Exception {
        startAndWaitForContinuousTransform(transformId, transformIndex, authHeader, 1L);
    }

    protected void startAndWaitForContinuousTransform(String transformId, String transformIndex, String authHeader, long checkpoint)
        throws Exception {
        // start the transform
        startTransform(transformId, authHeader, new String[0]);
        assertTrue(indexExists(transformIndex));
        // wait until the transform has been created and all data is available
        waitForTransformCheckpoint(transformId, checkpoint);
        refreshIndex(transformIndex);
    }

    protected void resetTransform(String transformId, boolean force) throws IOException {
        final Request resetTransformRequest = createRequestWithAuth("POST", getTransformEndpoint() + transformId + "/_reset", null);
        resetTransformRequest.addParameter(TransformField.FORCE.getPreferredName(), Boolean.toString(force));
        Map<String, Object> resetTransformResponse = entityAsMap(client().performRequest(resetTransformRequest));
        assertThat(resetTransformResponse.get("acknowledged"), equalTo(Boolean.TRUE));
    }

    protected Request createRequestWithSecondaryAuth(
        final String method,
        final String endpoint,
        final String authHeader,
        final String secondaryAuthHeader
    ) {
        final Request request = new Request(method, endpoint);

        RequestOptions.Builder options = request.getOptions().toBuilder();
        if (authHeader != null) {
            options.addHeader("Authorization", authHeader);
        }
        if (secondaryAuthHeader != null) {
            options.addHeader(SECONDARY_AUTH_KEY, secondaryAuthHeader);
        }
        request.setOptions(options);
        return request;
    }

    protected Request createRequestWithAuth(final String method, final String endpoint, final String authHeader) {
        return createRequestWithSecondaryAuth(method, endpoint, authHeader, null);
    }

    void waitForTransformStopped(String transformId) throws Exception {
        assertBusy(() -> { assertEquals("stopped", getTransformState(transformId)); }, 15, TimeUnit.SECONDS);
    }

    void waitForTransformCheckpoint(String transformId) throws Exception {
        waitForTransformCheckpoint(transformId, 1L);
    }

    void waitForTransformCheckpoint(String transformId, long checkpoint) throws Exception {
        assertBusy(() -> assertEquals(checkpoint, getTransformCheckpoint(transformId)), 30, TimeUnit.SECONDS);
    }

    void refreshIndex(String index) throws IOException {
        assertOK(client().performRequest(new Request("POST", index + "/_refresh")));
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> getTransforms(List<Map<String, String>> expectedErrors) throws IOException {
        Request request = new Request("GET", getTransformEndpoint() + "_all");
        Response response = adminClient().performRequest(request);
        Map<String, Object> transforms = entityAsMap(response);
        List<Map<String, Object>> transformConfigs = (List<Map<String, Object>>) XContentMapValues.extractValue("transforms", transforms);
        List<Map<String, String>> errors = (List<Map<String, String>>) XContentMapValues.extractValue("errors", transforms);
        assertThat(errors, is(equalTo(expectedErrors)));
        return transformConfigs == null ? Collections.emptyList() : transformConfigs;
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> getTransforms(int from, int size) throws IOException {
        Request request = new Request("GET", getTransformEndpoint() + "_all?from=" + from + "&size=" + size);
        Response response = adminClient().performRequest(request);
        return entityAsMap(response);
    }

    protected static String getTransformState(String transformId) throws IOException {
        Map<?, ?> transformStatsAsMap = getTransformStateAndStats(transformId);
        return transformStatsAsMap == null ? null : (String) XContentMapValues.extractValue("state", transformStatsAsMap);
    }

    protected static Map<?, ?> getTransformStateAndStats(String transformId) throws IOException {
        Response statsResponse = client().performRequest(new Request("GET", getTransformEndpoint() + transformId + "/_stats"));
        List<?> transforms = ((List<?>) entityAsMap(statsResponse).get("transforms"));
        if (transforms.isEmpty()) {
            return null;
        }
        return (Map<?, ?>) transforms.get(0);
    }

    protected static Map<String, Object> getTransformsStateAndStats(int from, int size) throws IOException {
        Response statsResponse = client().performRequest(
            new Request("GET", getTransformEndpoint() + "_stats?from=" + from + "&size=" + size)
        );
        return entityAsMap(statsResponse);
    }

    protected static void deleteTransform(String transformId) throws IOException {
        Request request = new Request("DELETE", getTransformEndpoint() + transformId);
        request.addParameter("ignore", "404"); // Ignore 404s because they imply someone was racing us to delete this
        adminClient().performRequest(request);
    }

    @After
    public void waitForTransform() throws Exception {
        ensureNoInitializingShards();
        logAudits();
        if (preserveClusterUponCompletion() == false) {
            adminClient().performRequest(new Request("POST", "/_features/_reset"));
        }
    }

    @AfterClass
    public static void removeIndices() throws Exception {
        // we might have disabled wiping indices, but now its time to get rid of them
        // note: can not use super.cleanUpCluster() as this method must be static
        wipeAllIndices();
    }

    static int getTransformCheckpoint(String transformId) throws IOException {
        Response statsResponse = client().performRequest(new Request("GET", getTransformEndpoint() + transformId + "/_stats"));

        Map<?, ?> transformStatsAsMap = (Map<?, ?>) ((List<?>) entityAsMap(statsResponse).get("transforms")).get(0);

        // assert that the transform did not fail
        assertNotEquals("failed", XContentMapValues.extractValue("state", transformStatsAsMap));
        return (int) XContentMapValues.extractValue("checkpointing.last.checkpoint", transformStatsAsMap);
    }

    protected void setupDataAccessRole(String role, String... indices) throws IOException {
        String indicesStr = Arrays.stream(indices).collect(Collectors.joining("\",\"", "\"", "\""));
        Request request = new Request("PUT", "/_security/role/" + role);
        request.setJsonEntity(Strings.format("""
            {
              "indices": [
                {
                  "names": [ %s ],
                  "privileges": [ "create_index", "read", "write", "view_index_metadata" ]
                }
              ]
            }""", indicesStr));
        client().performRequest(request);
    }

    protected void setupUser(String user, List<String> roles) throws IOException {
        String password = new String(TEST_PASSWORD_SECURE_STRING.getChars());

        String rolesStr = roles.stream().collect(Collectors.joining("\",\"", "\"", "\""));
        Request request = new Request("PUT", "/_security/user/" + user);
        request.setJsonEntity(Strings.format("""
            {  "password" : "%s",  "roles" : [ %s ]}
            """, password, rolesStr));
        client().performRequest(request);
    }

    protected void assertOnePivotValue(String query, double expected) throws IOException {
        Map<String, Object> searchResult = getAsMap(query);

        assertEquals(1, XContentMapValues.extractValue("hits.total.value", searchResult));
        double actual = (Double) ((List<?>) XContentMapValues.extractValue("hits.hits._source.avg_rating", searchResult)).get(0);
        assertEquals(expected, actual, 0.000001);
    }

    protected void assertOneCount(String query, String field, int expected) throws IOException {
        Map<String, Object> searchResult = getAsMap(query);

        assertEquals(1, XContentMapValues.extractValue("hits.total.value", searchResult));
        int actual = (Integer) ((List<?>) XContentMapValues.extractValue(field, searchResult)).get(0);
        assertEquals(expected, actual);
    }

    protected static String getTransformEndpoint() {
        return TransformField.REST_BASE_PATH_TRANSFORMS;
    }

    @SuppressWarnings("unchecked")
    private void logAudits() throws Exception {
        logger.info("writing audit messages to the log");
        Request searchRequest = new Request("GET", TransformInternalIndexConstants.AUDIT_INDEX + "/_search?ignore_unavailable=true");
        searchRequest.setJsonEntity("""
            {
              "size": 100,
              "sort": [ { "timestamp": { "order": "asc" } } ]
            }""");

        assertBusy(() -> {
            try {
                refreshIndex(TransformInternalIndexConstants.AUDIT_INDEX_PATTERN);
                Response searchResponse = client().performRequest(searchRequest);

                Map<String, Object> searchResult = entityAsMap(searchResponse);
                List<Map<String, Object>> searchHits = (List<Map<String, Object>>) XContentMapValues.extractValue(
                    "hits.hits",
                    searchResult
                );

                for (Map<String, Object> hit : searchHits) {
                    Map<String, Object> source = (Map<String, Object>) XContentMapValues.extractValue("_source", hit);
                    String level = (String) source.getOrDefault("level", "info");
                    logger.log(
                        Level.getLevel(level.toUpperCase(Locale.ROOT)),
                        "Transform audit: [{}] [{}] [{}] [{}]",
                        Instant.ofEpochMilli((long) source.getOrDefault("timestamp", 0)),
                        source.getOrDefault("transform_id", "n/a"),
                        source.getOrDefault("message", "n/a"),
                        source.getOrDefault("node_name", "n/a")
                    );
                }
            } catch (ResponseException e) {
                // see gh#54810, wrap temporary 503's as assertion error for retry
                if (e.getResponse().getStatusLine().getStatusCode() != 503) {
                    throw e;
                }
                throw new AssertionError("Failed to retrieve audit logs", e);
            }
        }, 5, TimeUnit.SECONDS);
    }
}

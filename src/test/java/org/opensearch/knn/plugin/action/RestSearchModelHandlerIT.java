/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.knn.plugin.action;

import org.apache.http.util.EntityUtils;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.knn.KNNRestTestCase;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.util.KNNEngine;
import org.opensearch.knn.indices.Model;
import org.opensearch.knn.indices.ModelMetadata;
import org.opensearch.knn.indices.ModelState;
import org.opensearch.knn.plugin.KNNPlugin;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.search.SearchHit;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.opensearch.knn.common.KNNConstants.MODELS;
import static org.opensearch.knn.common.KNNConstants.PARAM_SIZE;
import static org.opensearch.knn.common.KNNConstants.SEARCH_MODEL_MAX_SIZE;
import static org.opensearch.knn.common.KNNConstants.SEARCH_MODEL_MIN_SIZE;
import static org.opensearch.knn.index.SpaceType.L2;
import static org.opensearch.knn.index.util.KNNEngine.FAISS;

/**
 * Integration tests to check the correctness of {@link org.opensearch.knn.plugin.rest.RestSearchModelHandler}
 */

public class RestSearchModelHandlerIT extends KNNRestTestCase {

    private ModelMetadata getModelMetadata() {
        return new ModelMetadata(KNNEngine.DEFAULT, SpaceType.DEFAULT, 4, ModelState.CREATED, "2021-03-27", "test model", "");
    }

    public void testNotSupportedParams() throws IOException {
        createModelSystemIndex();
        String restURI = String.join("/", KNNPlugin.KNN_BASE_URI, MODELS, "_search");
        Map<String, String> invalidParams = new HashMap<>();
        invalidParams.put("index", "index-name");
        Request request = new Request("GET", restURI);
        request.addParameters(invalidParams);
        expectThrows(ResponseException.class, () -> client().performRequest(request));
    }

    public void testNoModelExists() throws IOException {
        createModelSystemIndex();
        String restURI = String.join("/", KNNPlugin.KNN_BASE_URI, MODELS, "_search");
        Request request = new Request("GET", restURI);
        request.setJsonEntity("{\n" + "    \"query\": {\n" + "        \"match_all\": {}\n" + "    }\n" + "}");

        Response response = client().performRequest(request);
        assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

        String responseBody = EntityUtils.toString(response.getEntity());
        assertNotNull(responseBody);

        XContentParser parser = createParser(XContentType.JSON.xContent(), responseBody);
        SearchResponse searchResponse = SearchResponse.fromXContent(parser);
        assertNotNull(searchResponse);
        assertEquals(searchResponse.getHits().getHits().length, 0);

    }

    public void testSizeValidationFailsInvalidSize() throws IOException {
        createModelSystemIndex();
        for (Integer invalidSize : Arrays.asList(SEARCH_MODEL_MIN_SIZE - 1, SEARCH_MODEL_MAX_SIZE + 1)) {
            String restURI = String.join("/", KNNPlugin.KNN_BASE_URI, MODELS, "_search?" + PARAM_SIZE + "=" + invalidSize);
            Request request = new Request("GET", restURI);

            ResponseException ex = expectThrows(ResponseException.class, () -> client().performRequest(request));
            String messageExpected = String.format(
                "%s must be between %s and %s inclusive",
                PARAM_SIZE,
                SEARCH_MODEL_MIN_SIZE,
                SEARCH_MODEL_MAX_SIZE
            );
            assertTrue(
                String.format("FAILED - Expected  \"%s\" to have \"%s\"", ex.getMessage(), messageExpected),
                ex.getMessage().contains(messageExpected)
            );
        }

    }

    public void testSearchModelExists() throws Exception {
        createModelSystemIndex();
        String trainingIndex = "irrelevant-index";
        String trainingFieldName = "train-field";
        int dimension = 8;
        String modelDescription = "dummy description";
        createBasicKnnIndex(trainingIndex, trainingFieldName, dimension);

        List<String> testModelID = Arrays.asList("test-modelid1", "test-modelid2");
        for (String modelId : testModelID) {
            ingestDataAndTrainModel(
                modelId,
                trainingIndex,
                trainingFieldName,
                dimension,
                modelDescription,
                xContentBuilderToMap(getModelMethodBuilder())
            );
            assertTrainingSucceeds(modelId, NUM_OF_ATTEMPTS, DELAY_MILLI_SEC);
        }

        String restURI = String.join("/", KNNPlugin.KNN_BASE_URI, MODELS, "_search");

        for (String method : Arrays.asList("GET", "POST")) {
            Request request = new Request(method, restURI);
            request.setJsonEntity("{\n" + "    \"query\": {\n" + "        \"match_all\": {}\n" + "    }\n" + "}");
            Response response = client().performRequest(request);
            assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

            String responseBody = EntityUtils.toString(response.getEntity());
            assertNotNull(responseBody);

            XContentParser parser = createParser(XContentType.JSON.xContent(), responseBody);
            SearchResponse searchResponse = SearchResponse.fromXContent(parser);
            assertNotNull(searchResponse);

            // returns only model from ModelIndex
            assertEquals(searchResponse.getHits().getHits().length, testModelID.size());

            for (SearchHit hit : searchResponse.getHits().getHits()) {
                assertTrue(testModelID.contains(hit.getId()));
                Model model = Model.getModelFromSourceMap(hit.getSourceAsMap());
                assertEquals(modelDescription, model.getModelMetadata().getDescription());
                assertEquals(FAISS, model.getModelMetadata().getKnnEngine());
                assertEquals(L2, model.getModelMetadata().getSpaceType());
            }
        }
    }

    public void testSearchModelWithoutSource() throws Exception {
        createModelSystemIndex();
        String trainingIndex = "irrelevant-index";
        String trainingFieldName = "train-field";
        int dimension = 8;
        createBasicKnnIndex(trainingIndex, trainingFieldName, dimension);

        List<String> testModelIds = Arrays.asList("test-modelid1", "test-modelid2");
        for (String modelId : testModelIds) {
            String modelDescription = "dummy description";
            ingestDataAndTrainModel(modelId, trainingIndex, trainingFieldName, dimension, modelDescription);
            assertTrainingSucceeds(modelId, NUM_OF_ATTEMPTS, DELAY_MILLI_SEC);
        }

        String restURI = String.join("/", KNNPlugin.KNN_BASE_URI, MODELS, "_search");

        for (String method : Arrays.asList("GET", "POST")) {
            Request request = new Request(method, restURI);
            request.setJsonEntity(
                "{\n" + "    \"_source\" : false,\n" + "    \"query\": {\n" + "        \"match_all\": {}\n" + "    }\n" + "}"
            );
            Response response = client().performRequest(request);
            assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

            String responseBody = EntityUtils.toString(response.getEntity());
            assertNotNull(responseBody);

            XContentParser parser = createParser(XContentType.JSON.xContent(), responseBody);
            SearchResponse searchResponse = SearchResponse.fromXContent(parser);
            assertNotNull(searchResponse);

            // returns only model from ModelIndex
            assertEquals(searchResponse.getHits().getHits().length, testModelIds.size());

            for (SearchHit hit : searchResponse.getHits().getHits()) {
                assertTrue(testModelIds.contains(hit.getId()));
                assertNull(hit.getSourceAsMap());
            }
        }
    }

    public void testSearchModelWithSourceFilteringIncludes() throws Exception {
        createModelSystemIndex();
        String trainingIndex = "irrelevant-index";
        String trainingFieldName = "train-field";
        int dimension = 8;
        createBasicKnnIndex(trainingIndex, trainingFieldName, dimension);

        List<String> testModelIds = Arrays.asList("test-modelid1", "test-modelid2");
        for (String modelId : testModelIds) {
            String modelDescription = "dummy description";
            ingestDataAndTrainModel(modelId, trainingIndex, trainingFieldName, dimension, modelDescription);
            assertTrainingSucceeds(modelId, NUM_OF_ATTEMPTS, DELAY_MILLI_SEC);
        }

        String restURI = String.join("/", KNNPlugin.KNN_BASE_URI, MODELS, "_search");

        for (String method : Arrays.asList("GET", "POST")) {
            Request request = new Request(method, restURI);
            request.setJsonEntity(
                "{\n"
                    + "    \"_source\": {\n"
                    + "        \"includes\": [ \"state\", \"description\" ]\n"
                    + "    }, "
                    + "    \"query\": {\n"
                    + "        \"match_all\": {}\n"
                    + "    }\n"
                    + "}"
            );
            Response response = client().performRequest(request);
            assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

            String responseBody = EntityUtils.toString(response.getEntity());
            assertNotNull(responseBody);

            XContentParser parser = createParser(XContentType.JSON.xContent(), responseBody);
            SearchResponse searchResponse = SearchResponse.fromXContent(parser);
            assertNotNull(searchResponse);

            // returns only model from ModelIndex
            assertEquals(searchResponse.getHits().getHits().length, testModelIds.size());

            for (SearchHit hit : searchResponse.getHits().getHits()) {
                assertTrue(testModelIds.contains(hit.getId()));
                Map<String, Object> sourceAsMap = hit.getSourceAsMap();
                assertFalse(sourceAsMap.containsKey("model_blob"));
                assertTrue(sourceAsMap.containsKey("state"));
                assertFalse(sourceAsMap.containsKey("timestamp"));
                assertTrue(sourceAsMap.containsKey("description"));
            }
        }
    }

    public void testSearchModelWithSourceFilteringExcludes() throws Exception {
        createModelSystemIndex();
        String trainingIndex = "irrelevant-index";
        String trainingFieldName = "train-field";
        int dimension = 8;
        createBasicKnnIndex(trainingIndex, trainingFieldName, dimension);

        List<String> testModelIds = Arrays.asList("test-modelid1", "test-modelid2");
        for (String modelId : testModelIds) {
            String modelDescription = "dummy description";
            ingestDataAndTrainModel(modelId, trainingIndex, trainingFieldName, dimension, modelDescription);
            assertTrainingSucceeds(modelId, NUM_OF_ATTEMPTS, DELAY_MILLI_SEC);
        }

        String restURI = String.join("/", KNNPlugin.KNN_BASE_URI, MODELS, "_search");

        for (String method : Arrays.asList("GET", "POST")) {
            Request request = new Request(method, restURI);
            request.setJsonEntity(
                "{\n"
                    + "    \"_source\": {\n"
                    + "        \"excludes\": [\"model_blob\" ]\n"
                    + "    }, "
                    + "    \"query\": {\n"
                    + "        \"match_all\": {}\n"
                    + "    }\n"
                    + "}"
            );
            Response response = client().performRequest(request);
            assertEquals(RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

            String responseBody = EntityUtils.toString(response.getEntity());
            assertNotNull(responseBody);

            XContentParser parser = createParser(XContentType.JSON.xContent(), responseBody);
            SearchResponse searchResponse = SearchResponse.fromXContent(parser);
            assertNotNull(searchResponse);

            // returns only model from ModelIndex
            assertEquals(searchResponse.getHits().getHits().length, testModelIds.size());

            for (SearchHit hit : searchResponse.getHits().getHits()) {
                assertTrue(testModelIds.contains(hit.getId()));
                Map<String, Object> sourceAsMap = hit.getSourceAsMap();
                assertFalse(sourceAsMap.containsKey("model_blob"));
                assertTrue(sourceAsMap.containsKey("state"));
                assertTrue(sourceAsMap.containsKey("timestamp"));
                assertTrue(sourceAsMap.containsKey("description"));
            }
        }
    }
}

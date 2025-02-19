/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.query;

import com.google.common.collect.Comparators;
import com.google.common.collect.ImmutableMap;
import lombok.SneakyThrows;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.Version;
import org.junit.Before;
import org.junit.BeforeClass;
import org.mockito.MockedStatic;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.knn.KNNTestCase;
import org.opensearch.knn.index.KNNSettings;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.codec.KNNCodecVersion;
import org.opensearch.knn.index.codec.util.KNNVectorAsArraySerializer;
import org.opensearch.knn.index.memory.NativeMemoryAllocation;
import org.opensearch.knn.index.memory.NativeMemoryCacheManager;
import org.opensearch.knn.index.util.KNNEngine;
import org.opensearch.knn.indices.ModelDao;
import org.opensearch.knn.indices.ModelMetadata;
import org.opensearch.knn.jni.JNIService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.opensearch.knn.KNNRestTestCase.INDEX_NAME;
import static org.opensearch.knn.common.KNNConstants.KNN_ENGINE;
import static org.opensearch.knn.common.KNNConstants.MODEL_ID;
import static org.opensearch.knn.common.KNNConstants.SPACE_TYPE;

public class KNNWeightTests extends KNNTestCase {
    private static final String FIELD_NAME = "target_field";
    private static final float[] QUERY_VECTOR = new float[] { 1.8f, 2.4f };
    private static final String SEGMENT_NAME = "0";
    private static final int K = 5;
    private static final Set<String> SEGMENT_FILES_NMSLIB = Set.of("_0.cfe", "_0_2011_target_field.hnswc");
    private static final Set<String> SEGMENT_FILES_FAISS = Set.of("_0.cfe", "_0_2011_target_field.faissc");
    private static final String CIRCUIT_BREAKER_LIMIT_100KB = "100Kb";

    private static final Map<Integer, Float> DOC_ID_TO_SCORES = Map.of(10, 0.4f, 101, 0.05f, 100, 0.8f, 50, 0.52f);
    private static final Map<Integer, Float> FILTERED_DOC_ID_TO_SCORES = Map.of(101, 0.05f, 100, 0.8f, 50, 0.52f);
    private static final Map<Integer, Float> EXACT_SEARCH_DOC_ID_TO_SCORES = Map.of(0, 0.12048191f);

    private static final Query FILTER_QUERY = new TermQuery(new Term("foo", "fooValue"));

    private static MockedStatic<NativeMemoryCacheManager> nativeMemoryCacheManagerMockedStatic;
    private static MockedStatic<JNIService> jniServiceMockedStatic;

    private static MockedStatic<KNNSettings> knnSettingsMockedStatic;

    @BeforeClass
    public static void setUpClass() throws Exception {
        final KNNSettings knnSettings = mock(KNNSettings.class);
        knnSettingsMockedStatic = mockStatic(KNNSettings.class);
        when(knnSettings.getSettingValue(eq(KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_ENABLED))).thenReturn(true);
        when(knnSettings.getSettingValue(eq(KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_LIMIT))).thenReturn(CIRCUIT_BREAKER_LIMIT_100KB);
        when(knnSettings.getSettingValue(eq(KNNSettings.KNN_CACHE_ITEM_EXPIRY_ENABLED))).thenReturn(false);
        when(knnSettings.getSettingValue(eq(KNNSettings.KNN_CACHE_ITEM_EXPIRY_TIME_MINUTES))).thenReturn(TimeValue.timeValueMinutes(10));

        final ByteSizeValue v = ByteSizeValue.parseBytesSizeValue(
            CIRCUIT_BREAKER_LIMIT_100KB,
            KNNSettings.KNN_MEMORY_CIRCUIT_BREAKER_LIMIT
        );
        knnSettingsMockedStatic.when(KNNSettings::getCircuitBreakerLimit).thenReturn(v);
        knnSettingsMockedStatic.when(KNNSettings::state).thenReturn(knnSettings);
        knnSettingsMockedStatic.when(KNNSettings::isKNNPluginEnabled).thenReturn(true);

        jniServiceMockedStatic = mockStatic(JNIService.class);
        nativeMemoryCacheManagerMockedStatic = mockStatic(NativeMemoryCacheManager.class);

        final NativeMemoryCacheManager nativeMemoryCacheManager = mock(NativeMemoryCacheManager.class);
        final NativeMemoryAllocation nativeMemoryAllocation = mock(NativeMemoryAllocation.class);
        when(nativeMemoryCacheManager.get(any(), anyBoolean())).thenReturn(nativeMemoryAllocation);

        nativeMemoryCacheManagerMockedStatic.when(NativeMemoryCacheManager::getInstance).thenReturn(nativeMemoryCacheManager);

        final MockedStatic<PathUtils> pathUtilsMockedStatic = mockStatic(PathUtils.class);
        final Path indexPath = mock(Path.class);
        when(indexPath.toString()).thenReturn("/mydrive/myfolder");
        pathUtilsMockedStatic.when(() -> PathUtils.get(anyString(), anyString())).thenReturn(indexPath);
    }

    @Before
    public void setupBeforeTest() {
        knnSettingsMockedStatic.when(() -> KNNSettings.getFilteredExactSearchThreshold(INDEX_NAME)).thenReturn(0);
    }

    @SneakyThrows
    public void testQueryResultScoreNmslib() {
        for (SpaceType space : List.of(SpaceType.L2, SpaceType.L1, SpaceType.COSINESIMIL, SpaceType.INNER_PRODUCT, SpaceType.LINF)) {
            testQueryScore(space::scoreTranslation, SEGMENT_FILES_NMSLIB, Map.of(SPACE_TYPE, space.getValue()));
        }
    }

    @SneakyThrows
    public void testQueryResultScoreFaiss() {
        testQueryScore(
            SpaceType.L2::scoreTranslation,
            SEGMENT_FILES_FAISS,
            Map.of(SPACE_TYPE, SpaceType.L2.getValue(), KNN_ENGINE, KNNEngine.FAISS.getName())
        );
        // score translation for Faiss and inner product is different from default defined in Space enum
        testQueryScore(
            rawScore -> SpaceType.INNER_PRODUCT.scoreTranslation(-1 * rawScore),
            SEGMENT_FILES_FAISS,
            Map.of(SPACE_TYPE, SpaceType.INNER_PRODUCT.getValue(), KNN_ENGINE, KNNEngine.FAISS.getName())
        );
    }

    @SneakyThrows
    public void testQueryScoreForFaissWithModel() throws IOException {
        SpaceType spaceType = SpaceType.L2;
        final Function<Float, Float> scoreTranslator = spaceType::scoreTranslation;
        final String modelId = "modelId";
        jniServiceMockedStatic.when(() -> JNIService.queryIndex(anyLong(), any(), anyInt(), anyString(), any()))
            .thenReturn(getKNNQueryResults());

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME);

        ModelDao modelDao = mock(ModelDao.class);
        ModelMetadata modelMetadata = mock(ModelMetadata.class);
        when(modelMetadata.getKnnEngine()).thenReturn(KNNEngine.FAISS);
        when(modelMetadata.getSpaceType()).thenReturn(spaceType);
        when(modelDao.getMetadata(eq("modelId"))).thenReturn(modelMetadata);

        KNNWeight.initialize(modelDao);
        final KNNWeight knnWeight = new KNNWeight(query, 0.0f);

        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final FSDirectory directory = mock(FSDirectory.class);
        when(reader.directory()).thenReturn(directory);
        final SegmentInfo segmentInfo = new SegmentInfo(
            directory,
            Version.LATEST,
            Version.LATEST,
            SEGMENT_NAME,
            100,
            true,
            KNNCodecVersion.current().getDefaultCodecDelegate(),
            Map.of(),
            new byte[StringHelper.ID_LENGTH],
            Map.of(),
            Sort.RELEVANCE
        );
        segmentInfo.setFiles(SEGMENT_FILES_FAISS);
        final SegmentCommitInfo segmentCommitInfo = new SegmentCommitInfo(segmentInfo, 0, 0, 0, 0, 0, new byte[StringHelper.ID_LENGTH]);
        when(reader.getSegmentInfo()).thenReturn(segmentCommitInfo);

        final Path path = mock(Path.class);
        when(directory.getDirectory()).thenReturn(path);
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);
        when(fieldInfo.attributes()).thenReturn(Map.of());
        when(fieldInfo.getAttribute(eq(MODEL_ID))).thenReturn(modelId);

        final KNNScorer knnScorer = (KNNScorer) knnWeight.scorer(leafReaderContext);
        assertNotNull(knnScorer);
        final DocIdSetIterator docIdSetIterator = knnScorer.iterator();
        assertNotNull(docIdSetIterator);
        assertEquals(DOC_ID_TO_SCORES.size(), docIdSetIterator.cost());

        final List<Integer> actualDocIds = new ArrayList();
        final Map<Integer, Float> translatedScores = getTranslatedScores(scoreTranslator);
        for (int docId = docIdSetIterator.nextDoc(); docId != NO_MORE_DOCS; docId = docIdSetIterator.nextDoc()) {
            actualDocIds.add(docId);
            assertEquals(translatedScores.get(docId), knnScorer.score(), 0.01f);
        }
        assertEquals(docIdSetIterator.cost(), actualDocIds.size());
        assertTrue(Comparators.isInOrder(actualDocIds, Comparator.naturalOrder()));
    }

    @SneakyThrows
    public void testQueryScoreForFaissWithNonExistingModel() throws IOException {
        SpaceType spaceType = SpaceType.L2;
        final String modelId = "modelId";

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME);

        ModelDao modelDao = mock(ModelDao.class);
        ModelMetadata modelMetadata = mock(ModelMetadata.class);
        when(modelMetadata.getKnnEngine()).thenReturn(KNNEngine.FAISS);
        when(modelMetadata.getSpaceType()).thenReturn(spaceType);

        KNNWeight.initialize(modelDao);
        final KNNWeight knnWeight = new KNNWeight(query, 0.0f);

        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final FSDirectory directory = mock(FSDirectory.class);
        when(reader.directory()).thenReturn(directory);

        final Path path = mock(Path.class);
        when(directory.getDirectory()).thenReturn(path);
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);
        when(fieldInfo.attributes()).thenReturn(Map.of());
        when(fieldInfo.getAttribute(eq(MODEL_ID))).thenReturn(modelId);

        RuntimeException ex = expectThrows(RuntimeException.class, () -> knnWeight.scorer(leafReaderContext));
        assertEquals(String.format("Model \"%s\" does not exist.", modelId), ex.getMessage());
    }

    @SneakyThrows
    public void testShardWithoutFiles() {
        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME);
        final KNNWeight knnWeight = new KNNWeight(query, 0.0f);

        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final FSDirectory directory = mock(FSDirectory.class);
        when(reader.directory()).thenReturn(directory);

        final SegmentInfo segmentInfo = new SegmentInfo(
            directory,
            Version.LATEST,
            Version.LATEST,
            SEGMENT_NAME,
            100,
            false,
            KNNCodecVersion.current().getDefaultCodecDelegate(),
            Map.of(),
            new byte[StringHelper.ID_LENGTH],
            Map.of(),
            Sort.RELEVANCE
        );
        segmentInfo.setFiles(Set.of());
        final SegmentCommitInfo segmentCommitInfo = new SegmentCommitInfo(segmentInfo, 0, 0, 0, 0, 0, new byte[StringHelper.ID_LENGTH]);
        when(reader.getSegmentInfo()).thenReturn(segmentCommitInfo);

        final Path path = mock(Path.class);
        when(directory.getDirectory()).thenReturn(path);
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);

        final Scorer knnScorer = knnWeight.scorer(leafReaderContext);
        assertNull(knnScorer);
    }

    @SneakyThrows
    public void testEmptyQueryResults() {
        final KNNQueryResult[] knnQueryResults = new KNNQueryResult[] {};
        jniServiceMockedStatic.when(() -> JNIService.queryIndex(anyLong(), any(), anyInt(), anyString(), any()))
            .thenReturn(knnQueryResults);

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME);
        final KNNWeight knnWeight = new KNNWeight(query, 0.0f);

        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final FSDirectory directory = mock(FSDirectory.class);
        when(reader.directory()).thenReturn(directory);
        final SegmentInfo segmentInfo = new SegmentInfo(
            directory,
            Version.LATEST,
            Version.LATEST,
            SEGMENT_NAME,
            100,
            true,
            KNNCodecVersion.current().getDefaultCodecDelegate(),
            Map.of(),
            new byte[StringHelper.ID_LENGTH],
            Map.of(),
            Sort.RELEVANCE
        );
        segmentInfo.setFiles(SEGMENT_FILES_NMSLIB);
        final SegmentCommitInfo segmentCommitInfo = new SegmentCommitInfo(segmentInfo, 0, 0, 0, 0, 0, new byte[StringHelper.ID_LENGTH]);
        when(reader.getSegmentInfo()).thenReturn(segmentCommitInfo);

        final Path path = mock(Path.class);
        when(directory.getDirectory()).thenReturn(path);
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);

        final Scorer knnScorer = knnWeight.scorer(leafReaderContext);
        assertNull(knnScorer);
    }

    @SneakyThrows
    public void testANNWithFilterQuery_whenDoingANN_thenSuccess() {
        int k = 3;
        final int[] filterDocIds = new int[] { 0, 1, 2, 3, 4, 5 };
        jniServiceMockedStatic.when(() -> JNIService.queryIndex(anyLong(), any(), anyInt(), anyString(), eq(filterDocIds)))
            .thenReturn(getFilteredKNNQueryResults());
        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        final Bits liveDocsBits = mock(Bits.class);
        when(reader.maxDoc()).thenReturn(filterDocIds.length);
        when(reader.getLiveDocs()).thenReturn(liveDocsBits);
        for (int filterDocId : filterDocIds) {
            when(liveDocsBits.get(filterDocId)).thenReturn(true);
        }
        when(liveDocsBits.length()).thenReturn(1000);
        when(leafReaderContext.reader()).thenReturn(reader);

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, k, INDEX_NAME, FILTER_QUERY);
        final Weight filterQueryWeight = mock(Weight.class);
        final Scorer filterScorer = mock(Scorer.class);
        when(filterQueryWeight.scorer(leafReaderContext)).thenReturn(filterScorer);
        // Just to make sure that we are not hitting the exact search condition
        when(filterScorer.iterator()).thenReturn(DocIdSetIterator.all(filterDocIds.length + 1));

        final KNNWeight knnWeight = new KNNWeight(query, 0.0f, filterQueryWeight);

        final FSDirectory directory = mock(FSDirectory.class);
        when(reader.directory()).thenReturn(directory);
        final SegmentInfo segmentInfo = new SegmentInfo(
            directory,
            Version.LATEST,
            Version.LATEST,
            SEGMENT_NAME,
            100,
            true,
            KNNCodecVersion.current().getDefaultCodecDelegate(),
            Map.of(),
            new byte[StringHelper.ID_LENGTH],
            Map.of(),
            Sort.RELEVANCE
        );
        segmentInfo.setFiles(SEGMENT_FILES_FAISS);
        final SegmentCommitInfo segmentCommitInfo = new SegmentCommitInfo(segmentInfo, 0, 0, 0, 0, 0, new byte[StringHelper.ID_LENGTH]);
        when(reader.getSegmentInfo()).thenReturn(segmentCommitInfo);

        final Path path = mock(Path.class);
        when(directory.getDirectory()).thenReturn(path);
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        final Map<String, String> attributesMap = ImmutableMap.of(KNN_ENGINE, KNNEngine.FAISS.getName());

        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);
        when(fieldInfo.attributes()).thenReturn(attributesMap);

        final KNNScorer knnScorer = (KNNScorer) knnWeight.scorer(leafReaderContext);
        assertNotNull(knnScorer);

        final DocIdSetIterator docIdSetIterator = knnScorer.iterator();
        assertNotNull(docIdSetIterator);
        assertEquals(FILTERED_DOC_ID_TO_SCORES.size(), docIdSetIterator.cost());
        jniServiceMockedStatic.verify(() -> JNIService.queryIndex(anyLong(), any(), anyInt(), anyString(), eq(filterDocIds)));

        final List<Integer> actualDocIds = new ArrayList<>();
        final Map<Integer, Float> translatedScores = getTranslatedScores(SpaceType.L2::scoreTranslation);
        for (int docId = docIdSetIterator.nextDoc(); docId != NO_MORE_DOCS; docId = docIdSetIterator.nextDoc()) {
            actualDocIds.add(docId);
            assertEquals(translatedScores.get(docId), knnScorer.score(), 0.01f);
        }
        assertEquals(docIdSetIterator.cost(), actualDocIds.size());
        assertTrue(Comparators.isInOrder(actualDocIds, Comparator.naturalOrder()));
    }

    @SneakyThrows
    public void testANNWithFilterQuery_whenExactSearch_thenSuccess() {
        float[] vector = new float[] { 0.1f, 0.3f };
        int filterDocId = 0;
        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME, FILTER_QUERY);
        final Weight filterQueryWeight = mock(Weight.class);
        final Scorer filterScorer = mock(Scorer.class);
        when(filterQueryWeight.scorer(leafReaderContext)).thenReturn(filterScorer);
        // scorer will return 2 documents
        when(filterScorer.iterator()).thenReturn(DocIdSetIterator.all(1));
        when(reader.maxDoc()).thenReturn(1);
        final Bits liveDocsBits = mock(Bits.class);
        when(reader.getLiveDocs()).thenReturn(liveDocsBits);
        when(liveDocsBits.get(filterDocId)).thenReturn(true);

        final KNNWeight knnWeight = new KNNWeight(query, 0.0f, filterQueryWeight);
        final Map<String, String> attributesMap = ImmutableMap.of(KNN_ENGINE, KNNEngine.FAISS.getName(), SPACE_TYPE, SpaceType.L2.name());
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        final BinaryDocValues binaryDocValues = mock(BinaryDocValues.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);
        when(fieldInfo.attributes()).thenReturn(attributesMap);
        when(fieldInfo.getAttribute(SPACE_TYPE)).thenReturn(SpaceType.L2.name());
        when(fieldInfo.getName()).thenReturn(FIELD_NAME);
        when(reader.getBinaryDocValues(FIELD_NAME)).thenReturn(binaryDocValues);
        when(binaryDocValues.advance(filterDocId)).thenReturn(filterDocId);
        BytesRef vectorByteRef = new BytesRef(new KNNVectorAsArraySerializer().floatToByteArray(vector));
        when(binaryDocValues.binaryValue()).thenReturn(vectorByteRef);

        final KNNScorer knnScorer = (KNNScorer) knnWeight.scorer(leafReaderContext);
        assertNotNull(knnScorer);
        final DocIdSetIterator docIdSetIterator = knnScorer.iterator();
        assertNotNull(docIdSetIterator);
        assertEquals(EXACT_SEARCH_DOC_ID_TO_SCORES.size(), docIdSetIterator.cost());

        final List<Integer> actualDocIds = new ArrayList<>();
        for (int docId = docIdSetIterator.nextDoc(); docId != NO_MORE_DOCS; docId = docIdSetIterator.nextDoc()) {
            actualDocIds.add(docId);
            assertEquals(EXACT_SEARCH_DOC_ID_TO_SCORES.get(docId), knnScorer.score(), 0.01f);
        }
        assertEquals(docIdSetIterator.cost(), actualDocIds.size());
        assertTrue(Comparators.isInOrder(actualDocIds, Comparator.naturalOrder()));
    }

    @SneakyThrows
    public void testANNWithFilterQuery_whenExactSearchAndThresholdComputations_thenSuccess() {
        knnSettingsMockedStatic.when(() -> KNNSettings.getFilteredExactSearchThreshold(INDEX_NAME)).thenReturn(-1);
        float[] vector = new float[] { 0.1f, 0.3f };
        int filterDocId = 0;
        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME, FILTER_QUERY);
        final Weight filterQueryWeight = mock(Weight.class);
        final Scorer filterScorer = mock(Scorer.class);
        when(filterQueryWeight.scorer(leafReaderContext)).thenReturn(filterScorer);
        // scorer will return 2 documents
        when(filterScorer.iterator()).thenReturn(DocIdSetIterator.all(1));
        when(reader.maxDoc()).thenReturn(1);
        final Bits liveDocsBits = mock(Bits.class);
        when(reader.getLiveDocs()).thenReturn(liveDocsBits);
        when(liveDocsBits.get(filterDocId)).thenReturn(true);

        final KNNWeight knnWeight = new KNNWeight(query, 0.0f, filterQueryWeight);
        final Map<String, String> attributesMap = ImmutableMap.of(KNN_ENGINE, KNNEngine.FAISS.getName(), SPACE_TYPE, SpaceType.L2.name());
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        final BinaryDocValues binaryDocValues = mock(BinaryDocValues.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);
        when(fieldInfo.attributes()).thenReturn(attributesMap);
        when(fieldInfo.getAttribute(SPACE_TYPE)).thenReturn(SpaceType.L2.name());
        when(fieldInfo.getName()).thenReturn(FIELD_NAME);
        when(reader.getBinaryDocValues(FIELD_NAME)).thenReturn(binaryDocValues);
        when(binaryDocValues.advance(filterDocId)).thenReturn(filterDocId);
        BytesRef vectorByteRef = new BytesRef(new KNNVectorAsArraySerializer().floatToByteArray(vector));
        when(binaryDocValues.binaryValue()).thenReturn(vectorByteRef);

        final KNNScorer knnScorer = (KNNScorer) knnWeight.scorer(leafReaderContext);
        assertNotNull(knnScorer);
        final DocIdSetIterator docIdSetIterator = knnScorer.iterator();
        assertNotNull(docIdSetIterator);
        assertEquals(EXACT_SEARCH_DOC_ID_TO_SCORES.size(), docIdSetIterator.cost());

        final List<Integer> actualDocIds = new ArrayList<>();
        for (int docId = docIdSetIterator.nextDoc(); docId != NO_MORE_DOCS; docId = docIdSetIterator.nextDoc()) {
            actualDocIds.add(docId);
            assertEquals(EXACT_SEARCH_DOC_ID_TO_SCORES.get(docId), knnScorer.score(), 0.01f);
        }
        assertEquals(docIdSetIterator.cost(), actualDocIds.size());
        assertTrue(Comparators.isInOrder(actualDocIds, Comparator.naturalOrder()));
    }

    /**
     * This test ensure that we do the exact search when threshold settings are correct and not using filteredIds<=K
     * condition to do exact search.
     * FilteredIdThreshold: 10
     * FilteredIdThresholdPct: 10%
     * FilteredIdsCount: 6
     * liveDocs : null, as there is no deleted documents
     * MaxDoc: 100
     * K : 1
     */
    @SneakyThrows
    public void testANNWithFilterQuery_whenExactSearchViaThresholdSetting_thenSuccess() {
        knnSettingsMockedStatic.when(() -> KNNSettings.getFilteredExactSearchThreshold(INDEX_NAME)).thenReturn(10);
        float[] vector = new float[] { 0.1f, 0.3f };
        int k = 1;
        final int[] filterDocIds = new int[] { 0, 1, 2, 3, 4, 5 };

        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);
        when(reader.maxDoc()).thenReturn(100);
        when(reader.getLiveDocs()).thenReturn(null);
        final Weight filterQueryWeight = mock(Weight.class);
        final Scorer filterScorer = mock(Scorer.class);
        when(filterQueryWeight.scorer(leafReaderContext)).thenReturn(filterScorer);

        when(filterScorer.iterator()).thenReturn(DocIdSetIterator.all(filterDocIds.length));

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, k, INDEX_NAME, FILTER_QUERY);

        final KNNWeight knnWeight = new KNNWeight(query, 0.0f, filterQueryWeight);
        final Map<String, String> attributesMap = ImmutableMap.of(KNN_ENGINE, KNNEngine.FAISS.getName(), SPACE_TYPE, SpaceType.L2.name());
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        final BinaryDocValues binaryDocValues = mock(BinaryDocValues.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);
        when(fieldInfo.attributes()).thenReturn(attributesMap);
        when(fieldInfo.getAttribute(SPACE_TYPE)).thenReturn(SpaceType.L2.name());
        when(fieldInfo.getName()).thenReturn(FIELD_NAME);
        when(reader.getBinaryDocValues(FIELD_NAME)).thenReturn(binaryDocValues);
        when(binaryDocValues.advance(0)).thenReturn(0);
        BytesRef vectorByteRef = new BytesRef(new KNNVectorAsArraySerializer().floatToByteArray(vector));
        when(binaryDocValues.binaryValue()).thenReturn(vectorByteRef);

        final KNNScorer knnScorer = (KNNScorer) knnWeight.scorer(leafReaderContext);
        assertNotNull(knnScorer);
        final DocIdSetIterator docIdSetIterator = knnScorer.iterator();
        assertNotNull(docIdSetIterator);
        assertEquals(EXACT_SEARCH_DOC_ID_TO_SCORES.size(), docIdSetIterator.cost());

        final List<Integer> actualDocIds = new ArrayList<>();
        for (int docId = docIdSetIterator.nextDoc(); docId != NO_MORE_DOCS; docId = docIdSetIterator.nextDoc()) {
            actualDocIds.add(docId);
            assertEquals(EXACT_SEARCH_DOC_ID_TO_SCORES.get(docId), knnScorer.score(), 0.01f);
        }
        assertEquals(docIdSetIterator.cost(), actualDocIds.size());
        assertTrue(Comparators.isInOrder(actualDocIds, Comparator.naturalOrder()));
    }

    @SneakyThrows
    public void testANNWithFilterQuery_whenEmptyFilterIds_thenReturnEarly() {
        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final Weight filterQueryWeight = mock(Weight.class);
        final Scorer filterScorer = mock(Scorer.class);
        when(filterQueryWeight.scorer(leafReaderContext)).thenReturn(filterScorer);
        when(filterScorer.iterator()).thenReturn(DocIdSetIterator.empty());

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME, FILTER_QUERY);
        final KNNWeight knnWeight = new KNNWeight(query, 0.0f, filterQueryWeight);

        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);

        final Scorer knnScorer = knnWeight.scorer(leafReaderContext);
        assertNotNull(knnScorer);
        final DocIdSetIterator docIdSetIterator = knnScorer.iterator();
        assertNotNull(docIdSetIterator);
        assertEquals(0, docIdSetIterator.cost());
        assertEquals(0, docIdSetIterator.cost());
    }

    private void testQueryScore(
        final Function<Float, Float> scoreTranslator,
        final Set<String> segmentFiles,
        final Map<String, String> fileAttributes
    ) throws IOException {
        jniServiceMockedStatic.when(() -> JNIService.queryIndex(anyLong(), any(), anyInt(), anyString(), any()))
            .thenReturn(getKNNQueryResults());

        final KNNQuery query = new KNNQuery(FIELD_NAME, QUERY_VECTOR, K, INDEX_NAME);
        final KNNWeight knnWeight = new KNNWeight(query, 0.0f);

        final LeafReaderContext leafReaderContext = mock(LeafReaderContext.class);
        final SegmentReader reader = mock(SegmentReader.class);
        when(leafReaderContext.reader()).thenReturn(reader);

        final FSDirectory directory = mock(FSDirectory.class);
        when(reader.directory()).thenReturn(directory);
        final SegmentInfo segmentInfo = new SegmentInfo(
            directory,
            Version.LATEST,
            Version.LATEST,
            SEGMENT_NAME,
            100,
            true,
            KNNCodecVersion.current().getDefaultCodecDelegate(),
            Map.of(),
            new byte[StringHelper.ID_LENGTH],
            Map.of(),
            Sort.RELEVANCE
        );
        segmentInfo.setFiles(segmentFiles);
        final SegmentCommitInfo segmentCommitInfo = new SegmentCommitInfo(segmentInfo, 0, 0, 0, 0, 0, new byte[StringHelper.ID_LENGTH]);
        when(reader.getSegmentInfo()).thenReturn(segmentCommitInfo);

        final Path path = mock(Path.class);
        when(directory.getDirectory()).thenReturn(path);
        final FieldInfos fieldInfos = mock(FieldInfos.class);
        final FieldInfo fieldInfo = mock(FieldInfo.class);
        when(reader.getFieldInfos()).thenReturn(fieldInfos);
        when(fieldInfos.fieldInfo(any())).thenReturn(fieldInfo);
        when(fieldInfo.attributes()).thenReturn(fileAttributes);

        final KNNScorer knnScorer = (KNNScorer) knnWeight.scorer(leafReaderContext);
        assertNotNull(knnScorer);
        final DocIdSetIterator docIdSetIterator = knnScorer.iterator();
        assertNotNull(docIdSetIterator);
        assertEquals(DOC_ID_TO_SCORES.size(), docIdSetIterator.cost());

        final List<Integer> actualDocIds = new ArrayList();
        final Map<Integer, Float> translatedScores = getTranslatedScores(scoreTranslator);
        for (int docId = docIdSetIterator.nextDoc(); docId != NO_MORE_DOCS; docId = docIdSetIterator.nextDoc()) {
            actualDocIds.add(docId);
            assertEquals(translatedScores.get(docId), knnScorer.score(), 0.01f);
        }
        assertEquals(docIdSetIterator.cost(), actualDocIds.size());
        assertTrue(Comparators.isInOrder(actualDocIds, Comparator.naturalOrder()));
    }

    private Map<Integer, Float> getTranslatedScores(Function<Float, Float> scoreTranslator) {
        return DOC_ID_TO_SCORES.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> scoreTranslator.apply(entry.getValue())));
    }

    private KNNQueryResult[] getKNNQueryResults() {
        return DOC_ID_TO_SCORES.entrySet()
            .stream()
            .map(entry -> new KNNQueryResult(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList())
            .toArray(new KNNQueryResult[0]);
    }

    private KNNQueryResult[] getFilteredKNNQueryResults() {
        return FILTERED_DOC_ID_TO_SCORES.entrySet()
            .stream()
            .map(entry -> new KNNQueryResult(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList())
            .toArray(new KNNQueryResult[0]);
    }
}

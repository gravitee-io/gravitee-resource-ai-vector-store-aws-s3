/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
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
package io.gravitee.resource.ai.vector.store.redis;

import static io.gravitee.resource.ai.vector.store.api.IndexType.HNSW;
import static io.vertx.redis.client.ResponseType.MULTI;
import static java.util.Comparator.comparingDouble;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.resource.ai.vector.store.api.*;
import io.gravitee.resource.ai.vector.store.redis.configuration.AiVectorStoreRedisConfiguration;
import io.gravitee.resource.ai.vector.store.redis.configuration.DistanceMetric;
import io.gravitee.resource.ai.vector.store.redis.configuration.RedisConfiguration;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.vertx.core.json.Json;
import io.vertx.redis.client.*;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.impl.AsyncResultMaybe;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */

@Slf4j
public class AiVectorStoreRedisResource extends AiVectorStoreResource<AiVectorStoreRedisConfiguration> {

  private static final String VECTOR_PARAM = "vector";
  private static final String MAX_RESULTS_PARAM = "max_results";

  private static final String VECTOR_TYPE_PROP_KEY = "TYPE";
  private static final String DIM_TYPE_PROP_KEY = "DIM";
  private static final String DISTANCE_METRIC_PROP_KEY = "DISTANCE_METRIC";
  private static final String INITIAL_CAP_PROP_KEY = "INITIAL_CAP";
  private static final String BLOCK_SIZE_PROP_KEY = "BLOCK_SIZE";
  private static final String M_PROP_KEY = "M";
  private static final String EF_CONSTRUCTION_PROP_KEY = "EF_CONSTRUCTION";
  private static final String EF_RUNTIME_PROP_KEY = "EF_RUNTIME";
  private static final String EPSILON_PROP_KEY = "EPSILON";
  private static final int HNSW_NB_PARAM = 16;
  private static final int FLAT_NB_PARAMS = 10;

  private static final String VECTOR_ATTR = "vector";
  private static final String TEXT_ATTR = "text";
  private static final String RETRIEVAL_CONTEXT_KEY_ATTR = "retrieval_context_key";

  private static final int REDIS_DIALECT_VALUE = 2;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final Pattern PARAMETERS_PATTERN = Pattern.compile("\\$(?!vector\\b|max_results\\b)(\\w+)");

  private static final String OK_REDIS_RESPONSE = "OK";
  private static final String AS = "AS";
  private static final String VECTOR_TYPE_DEF = "VECTOR";
  private static final String SCHEMA = "SCHEMA";
  private static final String PREFIX = "PREFIX";
  private static final int ONE_PREFIX = 1;
  public static final String JSON_PATH = "$";
  private static final String JSON_ROOT = JSON_PATH + "$.";
  private static final String TAG = "TAG";
  private static final String ON = "ON";
  private static final String JSON = "JSON";
  private static final String FT_SEARCH_NB_PARAMS_PROP_KEY = "PARAMS";
  private static final String DIALECT_KEY = "DIALECT";
  private static final String SORTBY_KET = "SORTBY";
  private static final String SORT_DIRECTION = "DESC";

  private static final String REDIS_RESULT_ATTR = "results";
  private static final String EXTRA_ATTRIBUTES_ATTR = "extra_attributes";
  private static final String ID_ATTR = "id";

  private AiVectorStoreProperties properties;
  private RedisConfiguration redisConfig;

  private Redis client;
  private Vertx vertx;

  @Override
  public void doStart() throws Exception {
    super.doStart();
    vertx = getBean(Vertx.class);

    properties = super.configuration().properties();
    redisConfig = super.configuration().redisConfig();
    client = buildClient();

    if (properties.readOnly()) {
      log.debug("AiVectorStoreRedisResource is read-only");
    } else {
      createIndex();
    }
  }

  private void createIndex() {
    indexExists(redisConfig.index())
      .switchIfEmpty(
        Maybe.defer(() -> {
          log.debug("Index [{}] already exists", redisConfig.index());
          return Maybe.empty();
        })
      )
      .map(this::createIndexRequest)
      .flatMap(this::rxSend)
      .subscribe(
        response -> {
          var content = response.toString();
          if (OK_REDIS_RESPONSE.equals(content)) {
            log.debug("Redis client created");
          } else {
            log.error("Could not create redis client: {}", content);
          }
        },
        e -> log.error("Error creating redis client", e)
      );
  }

  private Request createIndexRequest(String index) {
    var storeConfig = redisConfig.vectorStoreConfig();

    boolean isHnsw = HNSW.equals(properties.indexType());

    int numberOfParameters = isHnsw ? HNSW_NB_PARAM : FLAT_NB_PARAMS;

    Request ftCreateRequest = Request
      .cmd(Command.FT_CREATE)
      .arg(redisConfig.index())
      .arg(ON)
      .arg(JSON)
      .arg(PREFIX)
      .arg(ONE_PREFIX)
      .arg(getFinalPrefixName())
      .arg(SCHEMA)
      .arg(JSON_ROOT + RETRIEVAL_CONTEXT_KEY_ATTR)
      .arg(AS)
      .arg(RETRIEVAL_CONTEXT_KEY_ATTR)
      .arg(TAG)
      .arg(JSON_ROOT + VECTOR_ATTR)
      .arg(AS)
      .arg(VECTOR_ATTR)
      .arg(VECTOR_TYPE_DEF)
      .arg(getVectorAlgorithm())
      .arg(numberOfParameters)
      .arg(VECTOR_TYPE_PROP_KEY)
      .arg(storeConfig.vectorType().name())
      .arg(DIM_TYPE_PROP_KEY)
      .arg(properties.embeddingSize())
      .arg(DISTANCE_METRIC_PROP_KEY)
      .arg(getDistanceMetric().name())
      .arg(INITIAL_CAP_PROP_KEY)
      .arg(storeConfig.initialCapacity());

    if (isHnsw) {
      ftCreateRequest
        .arg(M_PROP_KEY)
        .arg(storeConfig.M())
        .arg(EF_CONSTRUCTION_PROP_KEY)
        .arg(storeConfig.efConstruction())
        .arg(EF_RUNTIME_PROP_KEY)
        .arg(storeConfig.efRuntime())
        .arg(EPSILON_PROP_KEY)
        .arg(storeConfig.epsilon());
    } else {
      ftCreateRequest.arg(BLOCK_SIZE_PROP_KEY).arg(Integer.toString(storeConfig.blockSize()));
    }

    return ftCreateRequest;
  }

  private Maybe<String> indexExists(String indexName) {
    // We return an empty field when index exists so that we stop the index c
    return rxSend(Request.cmd(Command.FT__LIST))
      .flatMap(response -> {
        if (MULTI.equals(response.type()) && response.size() > 0) {
          for (int i = 0; i < response.size(); i++) {
            if (indexName.equals(response.get(i).toString())) {
              return Maybe.empty();
            }
          }
        }
        return Maybe.just(indexName);
      });
  }

  private Redis buildClient() throws URISyntaxException {
    var clientOptions = new RedisOptions();
    clientOptions.setConnectionString(getUri().toASCIIString());
    clientOptions.setMaxPoolSize(redisConfig.maxPoolSize());

    return Redis.createClient(vertx.getDelegate(), clientOptions);
  }

  private URI getUri() throws URISyntaxException {
    URI uri = new URI(redisConfig.url());
    if (notEmpty(redisConfig.username()) && notEmpty(redisConfig.password())) {
      return getUsernameAndPasswordUri(uri);
    }
    return uri;
  }

  private boolean notEmpty(String value) {
    return value != null && !value.isBlank();
  }

  private URI getUsernameAndPasswordUri(URI u) throws URISyntaxException {
    return new URI(
      u.getScheme(),
      redisConfig.username() + ":" + redisConfig.password(),
      u.getHost(),
      u.getPort(),
      u.getPath(),
      u.getQuery(),
      u.getFragment()
    );
  }

  private String getFinalPrefixName() {
    return redisConfig.prefix() + ":";
  }

  private String getVectorAlgorithm() {
    return switch (properties.indexType()) {
      case FLAT, HNSW -> properties.indexType().name();
      case IVF -> throw new IllegalArgumentException("IVF not supported");
    };
  }

  private DistanceMetric getDistanceMetric() {
    return switch (properties.similarity()) {
      case EUCLIDEAN -> DistanceMetric.L2;
      case COSINE -> DistanceMetric.COSINE;
      case DOT -> DistanceMetric.IP;
    };
  }

  @Override
  public Completable add(VectorEntity vectorEntity) {
    if (properties.readOnly()) {
      log.debug("AiVectorStoreRedisResource.add is read-only");
      return Completable.complete();
    }
    Map<String, Object> doc = new HashMap<>();
    doc.put(VECTOR_ATTR, vectorEntity.vector());
    doc.put(TEXT_ATTR, vectorEntity.text());
    doc.putAll(vectorEntity.metadata());

    String id = getFinalPrefixName() + vectorEntity.id();
    String json = Json.encode(doc);

    Request jsonSetReq = Request.cmd(Command.JSON_SET).arg(id).arg("$").arg(json);

    Completable jsonSet = rxSend(jsonSetReq).ignoreElement();

    if (!properties.allowEviction()) {
      return jsonSet;
    }

    var expireAtRequest = getExpireAtRequest(id, vectorEntity.timestamp());
    return jsonSet.andThen(rxSend(expireAtRequest).ignoreElement());
  }

  private Request getExpireAtRequest(String id, long vectorTimestampMs) {
    long timestampSeconds = MILLISECONDS.toSeconds(vectorTimestampMs);
    long evictTimeSeconds = properties.evictTimeUnit().toSeconds(properties.evictTime());
    long expireAt = timestampSeconds + evictTimeSeconds;

    return Request.cmd(Command.EXPIREAT).arg(id).arg(expireAt);
  }

  @Override
  public Flowable<VectorResult> findRelevant(VectorEntity vectorEntity) {
    var vectorType = redisConfig.vectorStoreConfig().vectorType();

    return Maybe
      .fromCallable(() -> vectorType.toBytes(vectorEntity.vector()))
      .subscribeOn(Schedulers.io())
      .map(byteVector -> buildSearchRequest(vectorEntity, byteVector))
      .flatMap(this::rxSend)
      .onErrorResumeNext(e -> {
        log.debug("Could not search for similar vector entities", e);
        return Maybe.empty();
      })
      .toFlowable()
      .flatMap(searchResult -> {
        if (isMap(searchResult)) {
          var results = searchResult.get(REDIS_RESULT_ATTR);
          if (MULTI.equals(results.type()) && results.size() > 0) {
            var documents = new ArrayList<Document>(searchResult.size());
            results.forEach(response -> {
              Response extraAttributes = response.get(EXTRA_ATTRIBUTES_ATTR);
              documents.add(
                new Document(
                  response.get(ID_ATTR).toString(),
                  extraAttributes.get(redisConfig.scoreField()).toFloat(),
                  getMetadata(extraAttributes)
                )
              );
            });
            return Flowable.fromIterable(documents);
          }
        }
        return Flowable.empty();
      })
      .map(document -> {
        String text = (String) document.metadata().get(TEXT_ATTR);
        document.metadata().remove(TEXT_ATTR);
        document.metadata().remove(VECTOR_ATTR);
        return new VectorResult(new VectorEntity(document.id(), text, document.metadata()), normalizeSore(document.score()));
      })
      .sorted(comparingDouble(result -> -result.score()))
      .filter(result -> result.score() >= properties.threshold());
  }

  private static boolean isMap(Response searchResult) {
    return MULTI.equals(searchResult.type()) && !searchResult.getKeys().isEmpty();
  }

  private static Map<String, Object> getMetadata(Response extraAttributes) {
    try {
      if (extraAttributes.get(JSON_PATH) != null) {
        return OBJECT_MAPPER.readValue(extraAttributes.get(JSON_PATH).toString(), HashMap.class);
      }
      return extraAttributes
        .getKeys()
        .stream()
        .filter(key -> extraAttributes.get(key) != null && !key.equals(VECTOR_ATTR))
        .map(key -> Map.entry(key, extraAttributes.get(key).toString()))
        .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private float normalizeSore(float score) {
    return switch (this.properties.similarity()) {
      case EUCLIDEAN -> 2 / (2 + score);
      case COSINE, DOT -> (2 - score) / 2;
    };
  }

  public Request buildSearchRequest(VectorEntity vectorEntity, byte[] byteVector) {
    String queryString = redisConfig.query();
    String indexName = redisConfig.index();
    String scoreField = redisConfig.scoreField();

    var request = Request
      .cmd(Command.FT_SEARCH)
      .arg(indexName)
      .arg(queryString)
      .arg(DIALECT_KEY)
      .arg(REDIS_DIALECT_VALUE)
      .arg(SORTBY_KET)
      .arg(scoreField)
      .arg(SORT_DIRECTION);

    Map<String, String> params = new LinkedHashMap<>();
    Matcher matcher = PARAMETERS_PATTERN.matcher(queryString);
    while (matcher.find()) {
      String param = matcher.group().substring(1);
      Object value = vectorEntity.metadata().get(param);
      if (value != null) {
        params.put(param, value.toString());
      }
    }

    request.arg(FT_SEARCH_NB_PARAMS_PROP_KEY).arg((params.size() * 2) + 4);

    params.forEach((key, value) -> request.arg(key).arg(value));

    return request.arg(MAX_RESULTS_PARAM).arg(Integer.toString(properties.maxResults())).arg(VECTOR_PARAM).arg(byteVector);
  }

  @Override
  public void doStop() throws Exception {
    super.doStop();
    client.close();
  }

  @Override
  public void remove(VectorEntity vectorEntity) {
    throw new UnsupportedOperationException("AiVectorStoreRedisResource.remove not supported.");
  }

  public Maybe<Response> rxSend(Request command) {
    return AsyncResultMaybe.toMaybe(client.send(command)::onComplete);
  }

  private record Document(String id, float score, Map<String, Object> metadata) {}
}

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
package io.gravitee.resource.ai.vector.store.aws.s3;

import static org.junit.jupiter.api.Assertions.*;

import io.gravitee.resource.ai.vector.store.aws.s3.conversions.S3VectorsMetadataCodec;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;

public class S3VectorsMetadataCodecTest {

  // Test constants copied from the codec’s current behavior
  private static final String BLOB_KEY = "_meta_blob";
  private static final String JSON_PREFIX = "__json__:";
  private static final String B64_PREFIX = "__b64json__:";

  @Test
  @DisplayName("Primitives round-trip exactly (String, Boolean, Numbers as int or double)")
  void primitivesRoundTrip() {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("s", "hello");
    in.put("b", true);
    in.put("i", 42); // int
    in.put("l", 9_223_372_036_854_775_807L); // long (max)
    in.put("d", 3.14159); // double
    in.put("f", 2.71828f); // float

    Document doc = S3VectorsMetadataCodec.toS3Metadata(in, false);
    Map<String, Object> out = S3VectorsMetadataCodec.fromS3Metadata(doc);

    // Primitives round-trip as their natural Java types
    assertEquals("hello", out.get("s"));
    assertEquals(true, out.get("b"));
    assertEquals(42, out.get("i"));
    assertEquals(9_223_372_036_854_775_807L, out.get("l"));
    assertEquals(3.14159, ((Number) out.get("d")).doubleValue(), 0.00001);
    // Floats are restored as double, so cast for comparison
    assertEquals(2.71828, ((Number) out.get("f")).doubleValue(), 0.00001);
  }

  @Test
  @DisplayName("Lists of primitives round-trip contents as int or double")
  void listsOfPrimitivesRoundTrip() {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("list", List.of(1, 2, 3.14159, 2.71828f));
    Document doc = S3VectorsMetadataCodec.toS3Metadata(in, false);
    Map<String, Object> out = S3VectorsMetadataCodec.fromS3Metadata(doc);
    // Compare contents, not concrete type
    assertTrue(out.get("list") instanceof List);
    List<?> resultList = (List<?>) out.get("list");
    assertEquals(1, resultList.get(0));
    assertEquals(2, resultList.get(1));
    assertEquals(3.14159, ((Number) resultList.get(2)).doubleValue(), 0.00001);
    assertEquals(2.71828, ((Number) resultList.get(3)).doubleValue(), 0.00001);
  }

  @Test
  @DisplayName("Complex values are packed into _meta_blob (JSON prefix) and round-trip")
  void complexValuesGoToBlobAndBack() {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("s", "x");
    // Nested map + list-of-lists = complex
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("k1", List.of("a", "b"));
    nested.put("k2", Map.of("inner", 123));
    in.put("nested", nested);
    in.put("matrix", List.of(List.of(1, 2), List.of(3, 4)));

    Document doc = S3VectorsMetadataCodec.toS3Metadata(in, false);
    assertAllowedTopLevelTypes(doc);

    Map<String, Document> top = doc.asMap();
    assertEquals("x", top.get("s").asString());

    assertTrue(top.containsKey(BLOB_KEY), "blob should exist");
    assertTrue(top.get(BLOB_KEY).isString());
    String blob = top.get(BLOB_KEY).asString();
    assertTrue(blob.startsWith(JSON_PREFIX), "blob must be JSON-prefixed");

    Map<String, Object> out = S3VectorsMetadataCodec.fromS3Metadata(doc);
    assertEquals("x", out.get("s"));

    @SuppressWarnings("unchecked")
    Map<String, Object> decodedNested = (Map<String, Object>) out.get("nested");
    assertEquals(List.of("a", "b"), decodedNested.get("k1"));

    @SuppressWarnings("unchecked")
    Map<String, Object> inner = (Map<String, Object>) decodedNested.get("k2");
    assertEquals(123, inner.get("inner")); // Jackson decodes small ints as Integer

    @SuppressWarnings("unchecked")
    List<List<Integer>> matrix = (List<List<Integer>>) out.get("matrix");
    assertEquals(List.of(List.of(1, 2), List.of(3, 4)), matrix);
  }

  @Test
  @DisplayName("Base64 blob mode produces B64 prefix and round-trips")
  void base64BlobMode() {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("complex", Map.of("foo", "bar", "nums", List.of(1, 2, 3)));

    Document doc = S3VectorsMetadataCodec.toS3Metadata(in, true);
    String blob = doc.asMap().get(BLOB_KEY).asString();
    assertTrue(blob.startsWith(B64_PREFIX));

    Map<String, Object> out = S3VectorsMetadataCodec.fromS3Metadata(doc);
    @SuppressWarnings("unchecked")
    Map<String, Object> complex = (Map<String, Object>) out.get("complex");
    assertEquals("bar", complex.get("foo"));
    assertEquals(List.of(1, 2, 3), complex.get("nums"));
  }

  @Test
  @DisplayName("Nulls are dropped (no key emitted)")
  void nullsAreDropped() {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("keep", "yes");
    in.put("nada", null);

    Document doc = S3VectorsMetadataCodec.toS3Metadata(in, false);
    Map<String, Document> top = doc.asMap();
    assertTrue(top.containsKey("keep"));
    assertFalse(top.containsKey("nada"));
  }

  @Test
  @DisplayName("Top-level Document contains only allowed types for filterable metadata")
  void onlyAllowedTopLevelTypes() {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("s", "str");
    in.put("b", false);
    in.put("n", 0.0000000000000000000000001);
    in.put("listS", List.of("x", "y"));
    in.put("listN", List.of(1, 2, 3));
    in.put("complex", Map.of("deep", List.of("nope"))); // should go to blob

    Document doc = S3VectorsMetadataCodec.toS3Metadata(in, false);
    assertAllowedTopLevelTypes(doc);

    Map<String, Document> top = doc.asMap();
    assertTrue(top.containsKey(BLOB_KEY), "complex content should be in blob only");
    assertTrue(top.get(BLOB_KEY).isString());
  }

  @Nested
  @DisplayName("Precision tests for numbers")
  class PrecisionTests {

    @Test
    @DisplayName("Double and float round-trip as double with expected precision")
    void doubleAndFloatPrecision() {
      Map<String, Object> in = new LinkedHashMap<>();
      in.put("doubleVal", 0.1234567890123456);
      in.put("floatVal", 0.12345678f);
      Document doc = S3VectorsMetadataCodec.toS3Metadata(in, false);
      Map<String, Object> out = S3VectorsMetadataCodec.fromS3Metadata(doc);
      assertEquals(0.1234567890123456, ((Number) out.get("doubleVal")).doubleValue(), 0.000000000000001);
      assertEquals(0.12345678, ((Number) out.get("floatVal")).doubleValue(), 0.00000001);
    }
  }

  // ---------- helpers ----------

  private static void assertAllowedTopLevelTypes(Document doc) {
    assertTrue(doc.isMap(), "metadata Document must be a map");
    for (Map.Entry<String, Document> e : doc.asMap().entrySet()) {
      String k = e.getKey();
      Document v = e.getValue();
      assertTrue(
        v.isString() || v.isNumber() || v.isBoolean() || v.isList(),
        () -> "Top-level value for key '" + k + "' must be string/number/boolean/list"
      );
      if (v.isList()) {
        for (Document d : v.asList()) {
          assertTrue(
            d.isString() || d.isNumber() || d.isBoolean(),
            () -> "List under key '" + k + "' must contain only primitives"
          );
        }
      }
    }
  }
}

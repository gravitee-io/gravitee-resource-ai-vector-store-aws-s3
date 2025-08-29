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
package io.gravitee.resource.ai.vector.store.aws.s3.conversions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import software.amazon.awssdk.core.SdkNumber;
import software.amazon.awssdk.core.document.Document;

public final class S3VectorsMetadataCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BLOB_KEY = "_meta_blob"; // configure as NON-FILTERABLE at index creation
  private static final String JSON_PREFIX = "__json__:";
  private static final String B64_PREFIX = "__b64json__:";

  /** Map<String,Object> -> S3 Vectors metadata Document using MapBuilder.putX */
  public static Document toS3Metadata(Map<String, Object> in, boolean base64Blob) {
    Document.MapBuilder mb = Document.mapBuilder();
    Map<String, Object> complex = new LinkedHashMap<>();

    for (Map.Entry<String, Object> e : in.entrySet()) {
      String key = e.getKey();
      Object v = e.getValue();
      if (v == null) continue;

      if (isPrimitive(v)) {
        putPrimitive(mb, key, v); // uses putString/putBoolean/putNumber
      } else if (v instanceof Collection<?> c && isPrimitiveList(c)) {
        mb.putList(key, lb -> addPrimitiveList(lb, c)); // uses addString/addBoolean/addNumber
      } else {
        complex.put(key, v); // nested maps, lists-of-lists, POJOs -> blob
      }
    }

    // Remove BigDecimal and BigInteger handling: only support double, float, int, long
    // If complex is not empty, serialize as before
    if (!complex.isEmpty()) {
      String json;
      try {
        json = MAPPER.writeValueAsString(complex);
      } catch (Exception ex) {
        throw new IllegalArgumentException("Serialize complex metadata failed", ex);
      }

      String payload = base64Blob
        ? B64_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
        : JSON_PREFIX + json;

      mb.putString(BLOB_KEY, payload); // string field; declare as NON-FILTERABLE
    }

    return mb.build();
  }

  /** Document -> Map<String,Object> (round-trips complex values from _meta_blob) */
  public static Map<String, Object> fromS3Metadata(Document doc) {
    if (!doc.isMap()) throw new IllegalArgumentException("Metadata Document must be a map");
    Map<String, Object> out = new LinkedHashMap<>();

    for (Map.Entry<String, Document> e : doc.asMap().entrySet()) {
      String key = e.getKey();
      Document dv = e.getValue();

      if (dv.isString()) {
        String s = dv.asString();
        if (BLOB_KEY.equals(key) && s.startsWith(B64_PREFIX)) {
          String json = new String(Base64.getUrlDecoder().decode(s.substring(B64_PREFIX.length())), StandardCharsets.UTF_8);
          out.putAll(parseJsonMap(json));
        } else if (BLOB_KEY.equals(key) && s.startsWith(JSON_PREFIX)) {
          out.putAll(parseJsonMap(s.substring(JSON_PREFIX.length())));
        } else {
          out.put(key, s);
        }
      } else if (dv.isBoolean()) {
        out.put(key, dv.asBoolean());
      } else if (dv.isNumber()) {
        out.put(key, restoreNumberType(dv.asNumber()));
      } else if (dv.isList()) {
        List<Object> list = new ArrayList<>();
        for (Document d : dv.asList()) {
          if (d.isString()) list.add(d.asString()); else if (d.isBoolean()) list.add(d.asBoolean()); else if (
            d.isNumber()
          ) list.add(restoreNumberType(d.asNumber())); else list.add(d.unwrap()); // defensive
        }
        out.put(key, list);
      } else if (dv.isMap()) {
        out.put(key, unwrapMap(dv.asMap()));
      } else {
        out.put(key, dv.unwrap());
      }
    }
    return out;
  }

  // Restore number type from SdkNumber
  private static Number restoreNumberType(Number num) {
    if (num instanceof SdkNumber sdkNum) {
      String strVal = sdkNum.toString();
      try {
        if (strVal.contains(".")) {
          // Always return double for decimals
          return Double.parseDouble(strVal);
        } else {
          // Always return int for whole numbers (if possible), else long
          long l = Long.parseLong(strVal);
          if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            int i = Integer.parseInt(strVal);
            if (l == i) return i;
          }
          return l;
        }
      } catch (Exception e) {
        // Fallback: return as double if possible, else as long
        try {
          return Double.parseDouble(strVal);
        } catch (Exception ignored) {}
        try {
          return Long.parseLong(strVal);
        } catch (Exception ignored) {}
        // As a last resort, return as-is
        return sdkNum; // Should never happen
      }
    }
    // If not SdkNumber, return as-is
    return num;
  }

  // ---------- helpers ----------

  private static boolean isPrimitive(Object v) {
    return v instanceof String || v instanceof Number || v instanceof Boolean;
  }

  private static boolean isPrimitiveList(Collection<?> c) {
    for (Object o : c) if (!isPrimitive(o)) return false;
    return true;
  }

  private static void putPrimitive(Document.MapBuilder mb, String key, Object v) {
    if (v instanceof String s) {
      mb.putString(key, s);
      return;
    }
    if (v instanceof Boolean b) {
      mb.putBoolean(key, b);
      return;
    }
    if (v instanceof Number n) {
      mb.putNumber(key, n.toString());
      return;
    } // lossless via String overload
    throw new IllegalArgumentException("Not a primitive: " + v.getClass());
  }

  private static void addPrimitiveList(Document.ListBuilder lb, Collection<?> c) {
    for (Object o : c) {
      if (o instanceof String s) {
        lb.addString(s);
      } else if (o instanceof Boolean b) {
        lb.addBoolean(b);
      } else if (o instanceof Number n) {
        lb.addNumber(n.toString());
      } // lossless via String overload
      else throw new IllegalArgumentException("List contains non-primitive: " + o.getClass());
    }
  }

  private static Map<String, Object> parseJsonMap(String json) {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ex) {
      throw new IllegalArgumentException("Decode complex metadata failed", ex);
    }
  }

  private static Map<String, Object> unwrapMap(Map<String, Document> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Document> e : m.entrySet()) {
      Document d = e.getValue();
      if (d.isString()) out.put(e.getKey(), d.asString()); else if (d.isBoolean()) out.put(
        e.getKey(),
        d.asBoolean()
      ); else if (d.isNumber()) out.put(e.getKey(), restoreNumberType(d.asNumber())); else if (d.isList()) out.put(
        e.getKey(),
        d.unwrap()
      ); else if (d.isMap()) out.put(e.getKey(), unwrapMap(d.asMap())); else out.put(e.getKey(), d.unwrap());
    }
    return out;
  }
}

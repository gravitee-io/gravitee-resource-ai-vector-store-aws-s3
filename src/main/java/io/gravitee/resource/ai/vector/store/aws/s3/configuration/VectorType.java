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
package io.gravitee.resource.ai.vector.store.aws.s3.configuration;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

import java.nio.ByteBuffer;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum VectorType {
  BFLOAT16,
  FLOAT16,
  FLOAT32,
  FLOAT64;

  public byte[] toBytes(float[] input) {
    return switch (this) {
      case BFLOAT16 -> toBfloat16Bytes(input);
      case FLOAT16 -> toFloat16Bytes(input);
      case FLOAT32 -> toFloat32Bytes(input);
      case FLOAT64 -> toFloat64Bytes(input);
    };
  }

  private byte[] toBfloat16Bytes(float[] input) {
    ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES * input.length).order(LITTLE_ENDIAN);
    for (float f : input) {
      buffer.putShort((short) (Float.floatToRawIntBits(f) >>> 16));
    }
    return buffer.array();
  }

  public static byte[] toFloat16Bytes(float[] input) {
    ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES * input.length).order(LITTLE_ENDIAN);
    for (float f : input) {
      buffer.putShort(Float.floatToFloat16(f));
    }
    return buffer.array();
  }

  private static byte[] toFloat32Bytes(float[] input) {
    byte[] bytes = new byte[Float.BYTES * input.length];
    ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).asFloatBuffer().put(input);
    return bytes;
  }

  public static byte[] toFloat64Bytes(float[] input) {
    ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES * input.length).order(LITTLE_ENDIAN);
    for (float f : input) {
      buffer.putDouble(f);
    }
    return buffer.array();
  }
}

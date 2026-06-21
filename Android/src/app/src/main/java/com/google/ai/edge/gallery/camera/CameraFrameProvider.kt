/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.camera

import android.graphics.Bitmap
import kotlin.math.roundToInt

data class TimestampedFrame(val bitmap: Bitmap, val timestampMs: Long)

object CameraFrameProvider {
  private const val DEFAULT_LONG_EDGE_PX = 640

  @Volatile private var latestFrame: TimestampedFrame? = null

  @Synchronized
  fun update(bitmap: Bitmap, timestampMs: Long = System.currentTimeMillis()) {
    val resized = resizeToLongEdge(bitmap = bitmap, longEdgePx = DEFAULT_LONG_EDGE_PX)
    val frameBitmap = resized.copy(Bitmap.Config.ARGB_8888, false)
    if (resized !== bitmap) {
      resized.recycle()
    }

    val oldFrame = latestFrame
    latestFrame = TimestampedFrame(bitmap = frameBitmap, timestampMs = timestampMs)
    oldFrame?.bitmap?.recycle()
  }

  @Synchronized
  fun sampleLatest(): TimestampedFrame? {
    val frame = latestFrame ?: return null
    return TimestampedFrame(
      bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false),
      timestampMs = frame.timestampMs,
    )
  }

  fun resizeToLongEdge(bitmap: Bitmap, longEdgePx: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val longEdge = maxOf(width, height)
    if (longEdge <= 0 || longEdge == longEdgePx) {
      return bitmap
    }

    val scale = longEdgePx.toFloat() / longEdge.toFloat()
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
  }
}

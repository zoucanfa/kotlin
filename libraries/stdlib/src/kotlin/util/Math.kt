/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("NOTHING_TO_INLINE")

package kotlin

import java.lang.Math as jlMath

/**
 * Object, provides properties and methods for mathematical constants and functions.
 */
object Math {

    /** Ratio of the circumference of a circle to its diameter, approximately 3.14159. */
    const val PI: Double = jlMath.PI
    const val E: Double = jlMath.E


    inline fun sin(a: Double): Double = jlMath.sin(a)
    inline fun cos(a: Double): Double = jlMath.cos(a)
    inline fun tan(a: Double): Double = jlMath.tan(a)

    inline fun asin(a: Double): Double = jlMath.asin(a)
    inline fun acos(a: Double): Double = jlMath.acos(a)
    inline fun atan(a: Double): Double = jlMath.atan(a)
    inline fun atan2(y: Double, x: Double): Double = jlMath.atan2(y, x)

    inline fun hypot(x: Double, y: Double): Double = jlMath.hypot(x, y)

    inline fun pow(a: Double, b: Double): Double = jlMath.pow(a, b)
    inline fun sqrt(a: Double): Double = jlMath.sqrt(a)
    @Deprecated("Use pow(a, 1/3)", ReplaceWith("Math.pow(a, 1.0/3.0)"))
    inline fun cbrt(a: Double): Double = jlMath.cbrt(a)

    inline fun max(a: Int, b: Int): Int = jlMath.max(a, b)
    inline fun max(a: Long, b: Long): Long = jlMath.max(a, b)
    inline fun max(a: Float, b: Float): Float = jlMath.max(a, b)
    inline fun max(a: Double, b: Double): Double = jlMath.max(a, b)

    inline fun min(a: Int, b: Int): Int = jlMath.min(a, b)
    inline fun min(a: Long, b: Long): Long = jlMath.min(a, b)
    inline fun min(a: Float, b: Float): Float = jlMath.min(a, b)
    inline fun min(a: Double, b: Double): Double = jlMath.min(a, b)
}


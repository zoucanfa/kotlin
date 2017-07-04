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

package test.numbers

import kotlin.Math

import org.junit.Test

fun computeDistanceInMiles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadius = 3958.75
    val rads = lat1 / Math.PI
    val dLat = lat2 - lat1
    val dLng = lng2 - lng1
    val sindLat = Math.sin(dLat / 2)
    val sindLng = Math.sin(dLng / 2)
    val a = Math.pow(sindLat, 2.0) + Math.pow(sindLng, 2.0) * Math.cos(lat1) * Math.cos(lat2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    val dist = earthRadius * c

    return dist
}

class MathExample {
    @Test
    fun run() {
        println(computeDistanceInMiles(59.9391987, 30.3215199, 48.1380815, 11.5770459))

    }
}
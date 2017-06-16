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

package org.jetbrains.kotlin.android.parcel

import android.os.Parcel
import android.os.Parcelable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RobolectricTest {
    @Test
    fun simple() = withParcel { parcel ->
        parcel.writeInt(4)
        val bytes = parcel.marshall()
        parcel.unmarshall(bytes, 0, bytes.size)
        val x = parcel.readInt()
        assert(x == 4)
    }

    private fun withParcel(block: (Parcel) -> Unit) {
        val parcel = Parcel.obtain()
        try {
            block(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
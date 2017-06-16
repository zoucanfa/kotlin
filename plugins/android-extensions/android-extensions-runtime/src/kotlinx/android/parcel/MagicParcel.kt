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

package kotlinx.android.parcel

import android.os.Parcel
import android.os.Parcelable
import android.util.Size

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MagicParcel

class Test(val a: String, val b: Int) : Parcelable, Parcelable.Creator<Test> {

    fun test(size: Int): Array<Test?> {
        return arrayOfNulls<Test>(size)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        val list = this.a
        parcel.writeInt(list.length)
        for (item in list) {
            parcel.writeValue(item)
        }
    }

    override fun newArray(p0: Int): Array<Test> {
        val a = 100
        val b = if (a == 1) true else false


        TODO("not implemented")
    }

    fun abc(b: Boolean) {
        if (b) {
            println("A")
        } else {
            println("B")
        }
    }

    override fun createFromParcel(parcel: Parcel): Test {
        return Test(parcel.readString(), parcel.readInt())
    }

    override fun describeContents(): Int {
        return 0
    }
}
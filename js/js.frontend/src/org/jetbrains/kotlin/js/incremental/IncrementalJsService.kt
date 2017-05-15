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

package org.jetbrains.kotlin.js.incremental

import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.js.JsProtoBuf
import java.io.File

interface IncrementalJsService {
    fun processHeader(header: JsProtoBuf.Header)
    fun processPackagePart(sourceFile: File, packagePart: ProtoBuf.PackageFragment, binaryAst: ByteArray)

    companion object {
        val DO_NOTHING = object : IncrementalJsService {
            override fun processHeader(header: JsProtoBuf.Header) {
            }

            override fun processPackagePart(sourceFile: File, packagePart: ProtoBuf.PackageFragment, binaryAst: ByteArray) {
            }
        }
    }
}

data class PackagePartData(val file: File, val proto: ProtoBuf.PackageFragment, val binaryAst: ByteArray)

class IncrementalJsServiceImpl : IncrementalJsService {
    var headerProto: JsProtoBuf.Header? = null
        private set

    private val packagePartsData = arrayListOf<PackagePartData>()

    val packageParts: Iterable<PackagePartData>
        get() = packagePartsData

    override fun processHeader(header: JsProtoBuf.Header) {
        headerProto = header
    }

    override fun processPackagePart(sourceFile: File, packagePart: ProtoBuf.PackageFragment, binaryAst: ByteArray) {
        packagePartsData.add(PackagePartData(sourceFile, packagePart, binaryAst))
    }
}
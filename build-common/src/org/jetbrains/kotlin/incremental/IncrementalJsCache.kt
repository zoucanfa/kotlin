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

package org.jetbrains.kotlin.incremental

import com.intellij.util.io.DataExternalizer
import org.jetbrains.kotlin.incremental.js.IncrementalResultsConsumerImpl
import org.jetbrains.kotlin.incremental.storage.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.NameResolverImpl
import org.jetbrains.kotlin.serialization.js.JsProtoBuf
import org.jetbrains.kotlin.serialization.js.JsSerializerProtocol
import java.io.DataInput
import java.io.DataOutput
import java.io.File
import kotlin.collections.HashSet

open class IncrementalJsCache(cachesDir: File) : IncrementalCacheCommon(cachesDir) {
    companion object {
        private val TRANSLATION_RESULT_MAP = "translation-result"
        private val HEADER_FILE_NAME = "header.meta"
    }

    private val dirtySources = arrayListOf<File>()
    private val translationResults = registerMap(TranslationResultMap(TRANSLATION_RESULT_MAP.storageFile))

    private val headerFile: File
        get() = File(cachesDir, HEADER_FILE_NAME)

    var header: ByteArray
        get() = headerFile.readBytes()
        set(value) {
            cachesDir.mkdirs()
            headerFile.writeBytes(value)
        }

    override fun markDirty(removedAndCompiledSources: List<File>) {
        dirtySources.addAll(removedAndCompiledSources)
    }

    fun compareAndUpdate(translatedFiles: Iterable<IncrementalResultsConsumerImpl.PackagePartData>, changesCollector: ChangesCollector) {
        val sourcesAfterCompile = translatedFiles.mapTo(HashSet()) { it.sourceFile }
        // todo: add dirty symbols to result
        dirtySources.forEach {
            if (it !in sourcesAfterCompile) {
                translationResults.remove(it, changesCollector)
            }
        }
        dirtySources.clear()

        for ((src, proto, binaryAst) in translatedFiles) {
            translationResults.put(src, proto, binaryAst, changesCollector)
        }
    }

    fun packagePartsMetadata() =
        translationResults.keys().filter { File(it) !in dirtySources }.map { translationResults.get(it)!!.metadata }

    fun binaryTrees() =
        translationResults.keys().filter { File(it) !in dirtySources }.map { translationResults.get(it)!!.binaryAst }
}

private class TranslationResultValue(val metadata: ByteArray, val binaryAst: ByteArray)

private object TranslationResultValueExternalizer : DataExternalizer<TranslationResultValue> {
    override fun save(output: DataOutput, value: TranslationResultValue) {
        output.writeInt(value.metadata.size)
        output.write(value.metadata)

        output.writeInt(value.binaryAst.size)
        output.write(value.binaryAst)
    }

    override fun read(input: DataInput): TranslationResultValue {
        val metadataSize = input.readInt()
        val metadata = ByteArray(metadataSize)
        input.readFully(metadata)

        val binaryAstSize = input.readInt()
        val binaryAst = ByteArray(binaryAstSize)
        input.readFully(binaryAst)

        return TranslationResultValue(metadata = metadata, binaryAst = binaryAst)
    }
}

private class TranslationResultMap(storageFile: File) : BasicStringMap<TranslationResultValue>(storageFile, TranslationResultValueExternalizer) {
    override fun dumpValue(value: TranslationResultValue): String =
            "Metadata: ${value.metadata.md5String()}, Binary AST: ${value.binaryAst.md5String()}"

    fun put(file: File, newMetadata: ByteArray, newBinaryAst: ByteArray, changesCollector: ChangesCollector) {
        val oldValue = storage[file.canonicalPath]
        storage[file.canonicalPath] = TranslationResultValue(metadata = newMetadata, binaryAst = newBinaryAst)

        val oldProtoMap = oldValue?.metadata?.let { getProtoData(file, it) } ?: emptyMap()
        val newProtoMap = getProtoData(file, newMetadata)

        for (classId in oldProtoMap.keys + newProtoMap.keys) {
            changesCollector.collectProtoChanges(oldProtoMap[classId], newProtoMap[classId])
        }
    }

    fun get(key: String) = storage[key]

    fun keys() = storage.keys

    fun values(): Collection<TranslationResultValue> =
            storage.keys.map { storage[it]!! }

    fun remove(file: File, changesCollector: ChangesCollector) {
        val protoBytes = storage[file.canonicalPath]!!.metadata
        val protoMap = getProtoData(file, protoBytes)

        for ((_, protoData) in protoMap) {
            changesCollector.collectProtoChanges(oldData = protoData, newData = null)
        }
        storage.remove(file.canonicalPath)
    }
}

fun getProtoData(sourceFile: File, metadata: ByteArray): Map<ClassId, ProtoData>  {
    val classes = hashMapOf<ClassId, ProtoData>()
    val proto = ProtoBuf.PackageFragment.parseFrom(metadata, JsSerializerProtocol.extensionRegistry)
    val nameResolver = NameResolverImpl(proto.strings, proto.qualifiedNames)

    proto.class_List.forEach {
        val classId = nameResolver.getClassId(it.fqName)
        classes[classId] = ClassProtoData(it, nameResolver)
    }

    proto.`package`.apply {
        val packageFqName = if (hasExtension(JsProtoBuf.packageFqName)) {
            nameResolver.getPackageFqName(getExtension(JsProtoBuf.packageFqName))
        }
        else FqName.ROOT

        val packagePartClassId = ClassId(packageFqName, Name.identifier(sourceFile.nameWithoutExtension.capitalize() + "Kt"))
        classes[packagePartClassId] = PackagePartProtoData(this, nameResolver, packageFqName)
    }
    return classes
}
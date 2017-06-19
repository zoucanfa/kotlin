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

package org.jetbrains.kotlin.android.parcel.serializers

import com.intellij.util.containers.ConcurrentList
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns.*
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import java.util.*

interface ParcelSerializer {
    fun writeValue(v: InstructionAdapter)
    fun readValue(v: InstructionAdapter)

    companion object {
        fun get(type: KotlinType, asmType: Type, typeMapper: KotlinTypeMapper, forceBoxed: Boolean = false): ParcelSerializer = when {
            isPrimitiveTypeOrNullablePrimitiveType(type) -> {
                if (forceBoxed || type.isMarkedNullable)
                    wrapToNullAwareIfNeeded(type, BoxedPrimitiveTypeParcelSerializer.getInstance(asmType))
                else
                    PrimitiveTypeParcelSerializer.getInstance(asmType)
            }
            isStringOrNullableString(type) -> NullCompliantObjectParcelSerializer(
                    Method("writeString", "(Ljava/lang/String;)V"),
                    Method("readString", "()Ljava/lang/String;"))
            asmType.className == List::class.java.canonicalName
                    || asmType.className == ArrayList::class.java.canonicalName
                    || asmType.className == LinkedList::class.java.canonicalName
                    || asmType.className == ConcurrentList::class.java.canonicalName
                    || asmType.className == Set::class.java.canonicalName
                    || asmType.className == HashSet::class.java.canonicalName
                    || asmType.className == LinkedHashSet::class.java.canonicalName
                    || asmType.className == TreeSet::class.java.canonicalName
            -> {
                val elementType = type.arguments.single().type
                val elementSerializer = get(elementType, typeMapper.mapType(elementType), typeMapper, forceBoxed = true)
                CollectionParcelSerializer(asmType, elementSerializer)
            }
            type.isBoxedPrimitive() -> wrapToNullAwareIfNeeded(type, BoxedPrimitiveTypeParcelSerializer.getInstance(asmType))
            type.isBlob() -> NullCompliantObjectParcelSerializer(
                    Method("writeBlob", "([B)V"),
                    Method("readBlob", "()[B"))
            type.isSize() -> wrapToNullAwareIfNeeded(type, NullCompliantObjectParcelSerializer(
                    Method("writeSize", "(Landroid/util/Size;)V"),
                    Method("readSize", "()Landroid/util/Size;")))
            type.isSizeF() -> wrapToNullAwareIfNeeded(type, NullCompliantObjectParcelSerializer(
                    Method("writeSize", "(Landroid/util/SizeF;)V"),
                    Method("writeSize", "()Landroid/util/SizeF;")))
            else -> {
                // TODO Support custom collections here

                if (type.isSerializable()) {
                    NullCompliantObjectParcelSerializer(
                            Method("writeSerializable", "(Ljava/io/Serializable;)V"),
                            Method("readSerializable", "()Ljava/io/Serializable;"))
                }
                else {
                    GenericParcelSerializer
                }
            }
        }

        private fun wrapToNullAwareIfNeeded(type: KotlinType, serializer: ParcelSerializer) = when {
            type.isMarkedNullable -> NullAwareParcelSerializerWrapper(serializer)
            else -> serializer
        }

        private fun KotlinType.isSize() = matchesFqNameWithSupertypes("android.util.Size")
        private fun KotlinType.isSizeF() = matchesFqNameWithSupertypes("android.util.SizeF")
        private fun KotlinType.isSerializable() = matchesFqNameWithSupertypes("java.io.Serializable")

        private fun KotlinType.isBoxedPrimitive(): Boolean = when {
            matchesFqName(java.lang.Boolean::class.java.canonicalName) ||
            matchesFqName(java.lang.Character::class.java.canonicalName) ||
            matchesFqName(java.lang.Byte::class.java.canonicalName) ||
            matchesFqName(java.lang.Short::class.java.canonicalName) ||
            matchesFqName(java.lang.Integer::class.java.canonicalName) ||
            matchesFqName(java.lang.Float::class.java.canonicalName) ||
            matchesFqName(java.lang.Long::class.java.canonicalName) ||
            matchesFqName(java.lang.Double::class.java.canonicalName) -> true
            else -> false
        }

        private fun KotlinType.matchesFqNameWithSupertypes(fqName: String): Boolean {
            if (this.matchesFqName(fqName)) {
                return true
            }

            return this.constructor.supertypes.any { it.matchesFqName(fqName) }
        }

        private fun KotlinType.matchesFqName(fqName: String): Boolean {
            return this.constructor.declarationDescriptor?.fqNameSafe?.asString() == fqName
        }

        private fun KotlinType.isBlob(): Boolean {
            val primitiveArrayElementType = KotlinBuiltIns.getPrimitiveArrayElementType(this) ?: return false
            return primitiveArrayElementType == PrimitiveType.BYTE
        }
    }
}
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

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns.isPrimitiveType
import org.jetbrains.kotlin.builtins.KotlinBuiltIns.isString
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

interface ParcelSerializer {
    fun writeValue(v: InstructionAdapter)
    fun readValue(v: InstructionAdapter)

    companion object {
        fun get(type: KotlinType, asmType: Type, typeMapper: KotlinTypeMapper): ParcelSerializer = when {
            isPrimitiveType(type) -> PrimitiveTypeParcelSerializer(asmType)
            isString(type) -> NullCompliantObjectParcelSerializer(
                    Method("writeString", "(Ljava/lang/String;)V"),
                    Method("readString", "()Ljava/lang/String;"))
            asmType.className == List::class.java.canonicalName || asmType.className == Set::class.java.canonicalName -> {
                val elementType = type.arguments.single().type
                val elementSerializer = get(elementType, typeMapper.mapType(elementType), typeMapper)
                CollectionParcelSerializer(asmType, elementSerializer)
            }
            type.isBlob() -> NullCompliantObjectParcelSerializer(
                    Method("writeBlob", "([B)V"),
                    Method("readBlob", "()[B"))
            type.isSize() -> getObjectSerializer(type,
                    Method("writeSize", "(Landroid/util/Size;)V"),
                    Method("readSize", "()Landroid/util/Size;"))
            type.isSizeF() -> getObjectSerializer(type,
                    Method("writeSize", "(Landroid/util/SizeF;)V"),
                    Method("writeSize", "()Landroid/util/SizeF;"))
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

        private fun getObjectSerializer(type: KotlinType, writeMethod: Method, readMethod: Method) = when {
            type.isMarkedNullable -> NullAwareObjectParcelSerializer(writeMethod, readMethod)
            else -> NullCompliantObjectParcelSerializer(writeMethod, readMethod)
        }

        private fun KotlinType.isSize() = matchesFqNameWithSupertypes("android.util.Size")
        private fun KotlinType.isSizeF() = matchesFqNameWithSupertypes("android.util.SizeF")
        private fun KotlinType.isSerializable() = matchesFqNameWithSupertypes("java.io.Serializable")

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
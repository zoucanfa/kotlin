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

import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

private val PARCEL_TYPE = Type.getObjectType("android/os/Parcel")

internal object GenericParcelSerializer : ParcelSerializer {
    override fun writeValue(v: InstructionAdapter) {
        v.invokevirtual(PARCEL_TYPE.internalName, "writeValue", "(Ljava/lang/Object;)V", false)
    }

    override fun readValue(v: InstructionAdapter) {
        v.invokevirtual(PARCEL_TYPE.internalName, "readValue", "()Ljava/lang/Object;", false)
    }
}

internal class CollectionParcelSerializer(val asmType: Type, val elementSerializer: ParcelSerializer) : ParcelSerializer {
    private val listType: Type = Type.getObjectType(when (asmType.internalName) {
        "java/util/List" -> "java/util/ArrayList"
        "java/util/Set" -> "java/util/LinkedHashSet"
        else -> asmType.internalName
    })

    override fun writeValue(v: InstructionAdapter) = writeValueNullAware(v) {
        val labelIteratorLoop = Label()
        val labelReturn = Label()

        v.swap() // -> parcel, collection
        v.dupX1() // -> parcel, collection, parcel

        // Write collection type
        v.dup()
        v.aconst(asmType.internalName)
        v.invokevirtual("android/os/Parcel", "writeString", "(Ljava/lang/String;)V", false)

        // Write collection size
        v.invokeinterface("java/util/Collection", "size", "()I")
        v.invokevirtual("android/os/Parcel", "writeInt", "(I)V", false)

        // Iterate through elements
        v.invokeinterface("java/util/Collection", "iterator", "()Ljava/util/Iterator;")
        v.visitLabel(labelIteratorLoop)
        v.dup()
        v.invokeinterface("java/util/Iterator", "hasNext", "()Z")
        v.ifeq(labelReturn) // The list is empty

        v.dup()
        v.invokeinterface("java/util/Iterator", "next", "()Ljava/lang/Object;")
        v.load(1, PARCEL_TYPE)
        v.swap()

        elementSerializer.writeValue(v)
        v.goTo(labelIteratorLoop)

        v.visitLabel(labelReturn)
        v.pop()
    }

    override fun readValue(v: InstructionAdapter) = readValueNullAware(v) {
        val nextLoopIteration = Label()
        val loopIsOverLabel = Label()

        // Read list size
        v.load(1, PARCEL_TYPE)
        v.invokevirtual(PARCEL_TYPE.internalName, "readInt", "()I", false)

        v.anew(listType)
        v.dup()

        v.dupX2()
        v.invokespecial(listType.internalName, "<init>", "(I)V", false) // -> size, list

        v.visitLabel(nextLoopIteration)
        v.dupX1() // -> size, list, size
        v.ifeq(loopIsOverLabel) // -> size, list

        v.dup() // -> size, list, list

        v.load(1, PARCEL_TYPE)
        elementSerializer.readValue(v) // -> size, list, list, element

        v.invokevirtual(listType.internalName, "add", "(Ljava/lang/Object;)Z", false)
        v.pop() // -> size, list

        v.swap()
        v.aconst(-1)
        v.add(Type.INT_TYPE) // -> list, (size - 1)

        v.swap()
        v.goTo(nextLoopIteration)

        v.visitLabel(loopIsOverLabel)
    }
}

internal class NullAwareParcelSerializerWrapper(val delegate: ParcelSerializer) : ParcelSerializer {
    override fun writeValue(v: InstructionAdapter) = writeValueNullAware(v) {
        delegate.writeValue(v)
    }

    override fun readValue(v: InstructionAdapter) = readValueNullAware(v) {
        delegate.readValue(v)
    }
}

/** write...() and get...() methods in Android should support passing `null` values. */
internal class NullCompliantObjectParcelSerializer(val writeMethod: Method, val readMethod: Method) : ParcelSerializer {
    override fun writeValue(v: InstructionAdapter) {
        v.invokevirtual(PARCEL_TYPE.internalName, writeMethod.name, writeMethod.signature, false)
    }

    override fun readValue(v: InstructionAdapter) {
        v.invokevirtual(PARCEL_TYPE.internalName, readMethod.name, readMethod.signature, false)
    }
}

internal class BoxedPrimitiveTypeParcelSerializer private constructor(val boxedType: Type) : ParcelSerializer {
    companion object {
        private val BOXED_TYPE_MAPPINGS = mapOf(
                "java/lang/Boolean" to Type.BOOLEAN_TYPE,
                "java/lang/Character" to Type.CHAR_TYPE,
                "java/lang/Byte" to Type.BYTE_TYPE,
                "java/lang/Short" to Type.SHORT_TYPE,
                "java/lang/Integer" to Type.INT_TYPE,
                "java/lang/Float" to Type.FLOAT_TYPE,
                "java/lang/Long" to Type.LONG_TYPE,
                "java/lang/Double" to Type.DOUBLE_TYPE)

        private val BOXED_VALUE_METHOD_NAMES = mapOf(
                "java/lang/Boolean" to "booleanValue",
                "java/lang/Character" to "charValue",
                "java/lang/Byte" to "byteValue",
                "java/lang/Short" to "shortValue",
                "java/lang/Integer" to "intValue",
                "java/lang/Float" to "floatValue",
                "java/lang/Long" to "longValue",
                "java/lang/Double" to "doubleValue")

        private val INSTANCES = BOXED_TYPE_MAPPINGS.keys.map {
            val type = Type.getObjectType(it)
            type to BoxedPrimitiveTypeParcelSerializer(type)
        }.toMap()

        fun getInstance(type: Type) = INSTANCES[type] ?: error("Unsupported type $type")
    }

    val unboxedType = BOXED_TYPE_MAPPINGS[boxedType.internalName]!!
    val unboxedSerializer = PrimitiveTypeParcelSerializer.getInstance(unboxedType)
    val typeValueMethodName = BOXED_VALUE_METHOD_NAMES[boxedType.internalName]!!

    override fun writeValue(v: InstructionAdapter) {
        v.invokevirtual(boxedType.internalName, typeValueMethodName, "()${unboxedType.descriptor}", false)
        unboxedSerializer.writeValue(v)
    }

    override fun readValue(v: InstructionAdapter) {
        unboxedSerializer.readValue(v)
        v.invokestatic(boxedType.internalName, "valueOf", "(${unboxedType.descriptor})${boxedType.descriptor}", false)
    }
}

internal class PrimitiveTypeParcelSerializer private constructor(val type: Type) : ParcelSerializer {
    companion object {
        private val WRITE_METHOD_NAMES = mapOf(
                Type.BOOLEAN_TYPE to Method("writeInt", "(I)V"),
                Type.CHAR_TYPE to Method("writeInt", "(I)V"),
                Type.BYTE_TYPE to Method("writeByte", "(B)V"),
                Type.SHORT_TYPE to Method("writeInt", "(I)V"),
                Type.INT_TYPE to Method("writeInt", "(I)V"),
                Type.FLOAT_TYPE to Method("writeFloat", "(F)V"),
                Type.LONG_TYPE to Method("writeLong", "(J)V"),
                Type.DOUBLE_TYPE to Method("writeDouble", "(D)V"))

        private val READ_METHOD_NAMES = mapOf(
                Type.BOOLEAN_TYPE to Method("readInt", "()I"),
                Type.CHAR_TYPE to Method("readInt", "()I"),
                Type.BYTE_TYPE to Method("readByte", "()B"),
                Type.SHORT_TYPE to Method("readInt", "()I"),
                Type.INT_TYPE to Method("readInt", "()I"),
                Type.FLOAT_TYPE to Method("readFloat", "()F"),
                Type.LONG_TYPE to Method("readLong", "()J"),
                Type.DOUBLE_TYPE to Method("readDouble", "()D"))

        private val INSTANCES = READ_METHOD_NAMES.keys.map { it to PrimitiveTypeParcelSerializer(it) }.toMap()

        fun getInstance(type: Type) = INSTANCES[type] ?: error("Unsupported type ${type.descriptor}")
    }

    private val writeMethod = WRITE_METHOD_NAMES[type]!!
    private val readMethod = READ_METHOD_NAMES[type]!!

    override fun writeValue(v: InstructionAdapter) {
        v.invokevirtual(PARCEL_TYPE.internalName, writeMethod.name, writeMethod.signature, false)
    }

    override fun readValue(v: InstructionAdapter) {
        v.invokevirtual(PARCEL_TYPE.internalName, readMethod.name, readMethod.signature, false)
    }
}

private fun readValueNullAware(v: InstructionAdapter, block: () -> Unit) {
    val labelNull = Label()
    val labelReturn = Label()

    v.invokevirtual(PARCEL_TYPE.internalName, "readInt", "()I", false)
    v.ifeq(labelNull)

    v.load(1, PARCEL_TYPE)
    block()
    v.goTo(labelReturn)

    // Just push null on stack if the value is null
    v.visitLabel(labelNull)
    v.aconst(null)

    v.visitLabel(labelReturn)
}

private fun writeValueNullAware(v: InstructionAdapter, block: () -> Unit) {
    val labelReturn = Label()
    val labelNull = Label()
    v.dup()
    v.ifnull(labelNull)

    // Write 1 if non-null, 0 if null

    v.load(1, PARCEL_TYPE)
    v.aconst(1)
    v.invokevirtual(PARCEL_TYPE.internalName, "writeInt", "(I)V", false)
    block()

    v.goTo(labelReturn)

    labelNull@ v.visitLabel(labelNull)
    v.pop()
    v.aconst(0)
    v.invokevirtual(PARCEL_TYPE.internalName, "writeInt", "(I)V", false)

    labelReturn@ v.visitLabel(labelReturn)
}

internal class Method(val name: String, val signature: String)
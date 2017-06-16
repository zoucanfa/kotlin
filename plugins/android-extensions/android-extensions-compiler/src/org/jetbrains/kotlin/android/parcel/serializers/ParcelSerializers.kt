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
        TODO("not implemented")
    }
}

internal class NullAwareObjectParcelSerializer(val writeMethod: Method, val readMethod: Method) : ParcelSerializer {
    override fun writeValue(v: InstructionAdapter) = writeValueNullAware(v) {
        v.invokevirtual(PARCEL_TYPE.internalName, writeMethod.name, writeMethod.signature, false)
    }

    override fun readValue(v: InstructionAdapter) = readValueNullAware(v) {
        v.invokevirtual(PARCEL_TYPE.internalName, readMethod.name, readMethod.signature, false)
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

internal class PrimitiveTypeParcelSerializer(val type: Type) : ParcelSerializer {
    private companion object {
        val WRITE_METHOD_NAMES = mapOf(
                Type.BOOLEAN to Method("writeInt", "(I)V"),
                Type.CHAR to Method("writeInt", "(I)V"),
                Type.BYTE to Method("writeByte", "(B)V"),
                Type.SHORT to Method("writeInt", "(I)V"),
                Type.INT to Method("writeInt", "(I)V"),
                Type.FLOAT to Method("writeFloat", "(F)V"),
                Type.LONG to Method("writeLong", "(J)V"),
                Type.DOUBLE to Method("writeDouble", "(D)V"))

        val READ_METHOD_NAMES = mapOf(
                Type.BOOLEAN to Method("readInt", "()I"),
                Type.CHAR to Method("readInt", "()I"),
                Type.BYTE to Method("readByte", "()B"),
                Type.SHORT to Method("readInt", "()I"),
                Type.INT to Method("readInt", "()I"),
                Type.FLOAT to Method("readFloat", "()F"),
                Type.LONG to Method("readLong", "()J"),
                Type.DOUBLE to Method("readDouble", "()D"))
    }

    private val writeMethod = WRITE_METHOD_NAMES[type.sort] ?: error("Unsupported type ${type.descriptor}")
    private val readMethod = READ_METHOD_NAMES[type.sort] ?: error("Unsupported type ${type.descriptor}")

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
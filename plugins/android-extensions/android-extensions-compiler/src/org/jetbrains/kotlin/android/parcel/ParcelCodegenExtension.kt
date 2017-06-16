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

import kotlinx.android.parcel.MagicParcel
import org.jetbrains.kotlin.android.parcel.serializers.ParcelSerializer
import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.ImplementationBodyCodegen
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature
import org.jetbrains.kotlin.codegen.FunctionGenerationStrategy.CodegenBased
import org.jetbrains.kotlin.descriptors.impl.ClassDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.jetbrains.org.objectweb.asm.Type

class ParcelCodegenExtension : ExpressionCodegenExtension {
    private companion object {
        val MAGIC_PARCEL_CLASS_FQNAME = FqName(MagicParcel::class.java.canonicalName)
    }

    override fun generateClassSyntheticParts(codegen: ImplementationBodyCodegen) {
        val parcelableClass = codegen.descriptor
        if (!checkIfClassApplicable(parcelableClass)) return
        assert(parcelableClass.kind == ClassKind.CLASS || parcelableClass.kind == ClassKind.OBJECT)

        writeDescribeContentsIfNeeded(codegen, parcelableClass)
        writeCreatorAccessField(codegen)
        writeWriteToParcel(codegen, parcelableClass, getPropertiesToSerialize(codegen, parcelableClass))
    }

    override fun generateAssociatedClasses(codegen: ImplementationBodyCodegen) {
        val parcelableClass = codegen.descriptor
        if (!checkIfClassApplicable(parcelableClass)) return

        writeCreatorClass(codegen, parcelableClass, getPropertiesToSerialize(codegen, parcelableClass))
    }

    private fun getPropertiesToSerialize(
            codegen: ImplementationBodyCodegen,
            parcelableClass: ClassDescriptor
    ): List<Pair<String, KotlinType>> {
        val constructor = parcelableClass.constructors.first { it.isPrimary }

        val propertiesToSerialize = constructor.valueParameters.map { param ->
            codegen.bindingContext[BindingContext.VALUE_PARAMETER_AS_PROPERTY, param]
            ?: error("Value parameter should have 'val' or 'var' keyword")
        }

        return propertiesToSerialize.map { it.name.asString() /* TODO */ to it.type }
    }

    private fun checkIfClassApplicable(parcelableClass: ClassDescriptor): Boolean {
        return parcelableClass.annotations.hasAnnotation(MAGIC_PARCEL_CLASS_FQNAME)
    }

    private fun resolveParcelClassType(module: ModuleDescriptor): SimpleType {
        return module.findClassAcrossModuleDependencies(
                ClassId.topLevel(FqName("android.os.Parcel")))?.defaultType ?: error("Can't resolve 'android.os.Parcel' class")
    }

    private fun writeDescribeContentsIfNeeded(codegen: ImplementationBodyCodegen, parcelableClass: ClassDescriptor) {
        val methodName = "describeContents"

        val intType = parcelableClass.builtIns.intType
        if (parcelableClass.hasMethodDefined(methodName) { it.valueParameters.isEmpty() && it.returnType == intType }) return

        val hasFileDescriptorAnywhere = false

        parcelableClass.writeMethod(codegen, methodName, intType) {
            v.aconst(if (hasFileDescriptorAnywhere) 1 /* CONTENTS_FILE_DESCRIPTOR */ else 0)
            v.areturn(Type.INT_TYPE)
        }
    }

    private fun writeWriteToParcel(
            codegen: ImplementationBodyCodegen,
            parcelClass: ClassDescriptor,
            properties: List<Pair<String, KotlinType>>
    ) {
        val intType = parcelClass.builtIns.intType
        val parcelClassType = resolveParcelClassType(parcelClass.module)
        val parcelAsmType = codegen.typeMapper.mapType(parcelClassType)

        val containerAsmType = codegen.typeMapper.mapType(parcelClass.defaultType)

        parcelClass.writeMethod(codegen, "writeToParcel", null, "parcel" to parcelClassType, "flags" to intType) {
            for ((fieldName, type) in properties) {
                val asmType = codegen.typeMapper.mapType(type)

                v.load(1, parcelAsmType)
                v.load(0, containerAsmType)
                v.getfield(containerAsmType.internalName, fieldName, asmType.descriptor)

                val serializer = ParcelSerializer.get(type, asmType, codegen.typeMapper)
                serializer.writeValue(v)
            }

            v.areturn(Type.VOID_TYPE)
        }
    }

    private fun writeCreateFromParcel(
            codegen: ImplementationBodyCodegen,
            parcelableClass: ClassDescriptor,
            creatorClass: ClassDescriptorImpl,
            properties: List<Pair<String, KotlinType>>
    ) {
        val parcelClassType = resolveParcelClassType(parcelableClass.module)
        val parcelAsmType = codegen.typeMapper.mapType(parcelClassType)
        val containerAsmType = codegen.typeMapper.mapType(parcelableClass)

        creatorClass.writeMethod(codegen, "createFromParcel", parcelableClass.defaultType, "in" to parcelClassType) {
            v.anew(containerAsmType)

            val asmConstructorParameters = StringBuilder()

            for ((_, type) in properties) {
                val asmType = codegen.typeMapper.mapType(type)
                asmConstructorParameters.append(asmType.descriptor)

                val serializer = ParcelSerializer.get(type, asmType, codegen.typeMapper)
                v.load(1, parcelAsmType)
                serializer.readValue(v)
            }

            v.invokespecial(containerAsmType.internalName, "<init>", "($asmConstructorParameters)V", false)
            v.areturn(containerAsmType)
        }
    }

    private fun writeCreatorAccessField(codegen: ImplementationBodyCodegen) {
        codegen.v.newField(JvmDeclarationOrigin.NO_ORIGIN, ACC_STATIC and ACC_PUBLIC and ACC_FINAL, "CREATOR",
                           "Landroid/os/Parcelable\$Creator;", null, null)
    }

    private fun writeCreatorClass(
            codegen: ImplementationBodyCodegen,
            parcelableClass: ClassDescriptor,
            properties: List<Pair<String, KotlinType>>
    ) {
        val parcelableClassName = codegen.typeMapper.mapType(parcelableClass.defaultType)

        val creatorClass = ClassDescriptorImpl(
                parcelableClass.containingDeclaration, Name.identifier("Creator"), Modality.FINAL, ClassKind.CLASS, emptyList(),
                parcelableClass.source, false)

        codegen.v.defineClass(null, ASM5, ACC_PUBLIC or ACC_STATIC,
                              parcelableClassName.internalName + "\$CREATOR", null, "java/lang/Object",
                              arrayOf("android/os/Parcelable\$Creator"))

        writeNewArrayMethod(codegen, parcelableClass, creatorClass)
        writeCreateFromParcel(codegen, parcelableClass, creatorClass, properties)

        codegen.v.done()
    }

    private fun writeNewArrayMethod(
            codegen: ImplementationBodyCodegen,
            parcelableClass: ClassDescriptor,
            creatorClass: ClassDescriptorImpl
    ) {
        val builtIns = parcelableClass.builtIns
        val parcelableAsmType = codegen.typeMapper.mapType(parcelableClass)

        creatorClass.writeMethod(
                codegen, "newArray",
                builtIns.getArrayType(Variance.INVARIANT, parcelableClass.defaultType),
                "size" to builtIns.intType
        ) {
            v.load(1, Type.INT_TYPE)
            v.newarray(parcelableAsmType)
            v.areturn(Type.getType("[L$parcelableAsmType;"))
        }
    }

    private fun ClassDescriptor.writeMethod(
            codegen: ImplementationBodyCodegen,
            name: String,
            returnType: KotlinType? = null,
            vararg parameters: Pair<String, KotlinType>,
            code: ExpressionCodegen.() -> Unit
    ) {
        val functionDescriptor = SimpleFunctionDescriptorImpl.create(
                this,
                Annotations.EMPTY,
                Name.identifier(name),
                CallableMemberDescriptor.Kind.SYNTHESIZED,
                this.source)

        val valueParameters = parameters.mapIndexed { index, (name, type) -> functionDescriptor.makeValueParameter(name, type, index) }

        functionDescriptor.initialize(
                null, this.thisAsReceiverParameter, emptyList(), valueParameters,
                returnType ?: builtIns.unitType, Modality.FINAL, Visibilities.PUBLIC)

        codegen.functionCodegen.generateMethod(JvmDeclarationOrigin.NO_ORIGIN, functionDescriptor, object : CodegenBased(codegen.state) {
            override fun doGenerateBody(e: ExpressionCodegen, signature: JvmMethodSignature) {
                e.code()
            }
        })
    }

    private fun FunctionDescriptor.makeValueParameter(name: String, type: KotlinType, index: Int): ValueParameterDescriptor {
        return ValueParameterDescriptorImpl(
                this, null, index, Annotations.EMPTY, Name.identifier(name), type, false, false, false, null, this.source)
    }

    private fun ClassDescriptor.hasMethodDefined(name: String, filter: ((SimpleFunctionDescriptor) -> Boolean)? = null): Boolean {
        val allFunctions = unsubstitutedMemberScope.getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_BACKEND)
        return (if (filter == null || allFunctions.isEmpty()) allFunctions else allFunctions.filter(filter)).isNotEmpty()
    }
}
/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.js.translate.intrinsic.objects

import org.jetbrains.kotlin.builtins.CompanionObjectMapping
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.js.backend.ast.JsExpression
import org.jetbrains.kotlin.js.backend.ast.JsName
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.context.StaticContext
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.reference.ReferenceTranslator
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module

class DefaultClassObjectIntrinsic(val staticContext: StaticContext, val tag: String, val fqName: FqName) : ObjectIntrinsic {
    private val innerName: JsName by lazy {
        val declaration = JsAstUtils.replaceRootReference(staticContext.getQualifiedReference(fqName), Namer.kotlinObject())
        staticContext.importDeclaration(fqName.shortName().asString(), tag, declaration)
    }

    override fun apply(context: TranslationContext) = JsAstUtils.pureFqn(innerName, null)
}

class PrimitiveCompanionObjectInstrinsic(val descriptor: ClassDescriptor) : ObjectIntrinsic {
    override fun apply(context: TranslationContext): JsExpression {
        return ReferenceTranslator.translateAsValueReference(descriptor, context)
    }
}

class ObjectIntrinsics(private val staticContext: StaticContext) {
    private val cache = mutableMapOf<ClassDescriptor, ObjectIntrinsic>()

    fun getIntrinsic(classDescriptor: ClassDescriptor) = cache.getOrPut(classDescriptor) { createIntrinsic(classDescriptor) }

    private fun createIntrinsic(classDescriptor: ClassDescriptor): ObjectIntrinsic {
        if (classDescriptor.fqNameUnsafe == KotlinBuiltIns.FQ_NAMES._enum ||
            !CompanionObjectMapping.isMappedIntrinsicCompanionObject(classDescriptor)
        ) {
            return NO_OBJECT_INTRINSIC
        }

        val containingDeclaration = classDescriptor.containingDeclaration
        val name = Name.identifier(containingDeclaration.name.asString() + "CompanionObject")
        val fqName = FqName("kotlin.js.internal").child(name)
        val classId = ClassId.topLevel(fqName)
        val foundDescriptor = staticContext.currentModule.findClassAcrossModuleDependencies(classId)
        if (foundDescriptor != null && foundDescriptor.module == staticContext.currentModule) {
            return PrimitiveCompanionObjectInstrinsic(foundDescriptor)
        }

        return DefaultClassObjectIntrinsic(
                staticContext, "intrinsic:kotlin.js.internal.${name.asString()}", fqName)
    }
}

interface ObjectIntrinsic {
    fun apply(context: TranslationContext): JsExpression
    fun exists(): Boolean = true
}

object NO_OBJECT_INTRINSIC : ObjectIntrinsic {
    override fun apply(context: TranslationContext): JsExpression =
            throw UnsupportedOperationException("ObjectIntrinsic#NO_OBJECT_INTRINSIC_#apply")

    override fun exists(): Boolean = false
}

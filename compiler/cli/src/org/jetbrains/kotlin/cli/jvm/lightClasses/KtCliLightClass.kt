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

package org.jetbrains.kotlin.cli.jvm.lightClasses

import com.intellij.psi.*
import com.intellij.psi.impl.light.LightModifierList
import com.intellij.psi.impl.light.LightPsiClassBase
import com.intellij.psi.impl.light.LightTypeParameterBuilder
import com.intellij.psi.impl.light.LightTypeParameterListBuilder
import org.jetbrains.kotlin.asJava.KtLightClassMarker
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.load.java.structure.LightClassOriginKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe

class KtCliLightClassForSourceDeclaration(
        private val descriptor: ClassDescriptor,
        psiManager: PsiManager
) : KtCliLightClassBase(psiManager, descriptor.name.asString()) {
    override fun getQualifiedName() = descriptor.fqNameUnsafe.asString()

    override fun getTypeParameterList(): PsiTypeParameterList = LightTypeParameterListBuilder(manager, KotlinLanguage.INSTANCE).apply {
        descriptor.declaredTypeParameters.forEachIndexed { index, typeParameter ->
            addParameter(LightTypeParameterBuilder(typeParameter.name.asString(), this@KtCliLightClassForSourceDeclaration, index))
        }
    } // TODO:

    override fun getInnerClasses() = emptyArray<PsiClass>() // TODO:

    override fun getFields() = emptyArray<PsiField>()

    override fun getContainingFile() = DescriptorToSourceUtils.getContainingFile(descriptor)
}

class KtCliLightClassForFacade(
        psiManager: PsiManager,
        private val fqName: FqName,
        private val filesForFacade: Collection<KtFile>
) : KtCliLightClassBase(psiManager, fqName.shortName().asString()) {
    override fun getQualifiedName() = fqName.asString()

    override fun getTypeParameterList(): PsiTypeParameterList = LightTypeParameterListBuilder(manager, KotlinLanguage.INSTANCE) // TODO:

    override fun getInnerClasses() = emptyArray<PsiClass>()

    override fun getFields() = emptyArray<PsiField>()

    override fun getContainingFile() = filesForFacade.first()
}



abstract class KtCliLightClassBase(psiManager: PsiManager, name: String) : LightPsiClassBase(psiManager, KotlinLanguage.INSTANCE, name), KtLightClassMarker {
    override fun getImplementsList(): PsiReferenceList? = null

    override fun getMethods(): Array<PsiMethod> = emptyArray()

    override fun getInitializers(): Array<PsiClassInitializer> = emptyArray()

    override fun getContainingClass(): PsiClass? = null // TODO:

    override fun getExtendsList() = null

    override fun getModifierList(): PsiModifierList = LightModifierList(manager, KotlinLanguage.INSTANCE)

    override fun getScope() = containingFile // TODO:

    override val originKind: LightClassOriginKind
        get() = LightClassOriginKind.SOURCE
}
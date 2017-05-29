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

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.JvmCommonIntentionActionsFactory
import com.intellij.codeInsight.intention.NewCallableMemberInfo
import com.intellij.lang.Language
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.uast.*
import org.junit.Assert


class CommonIntentionActionsTest : LightPlatformCodeInsightFixtureTestCase() {

    fun testMakeNotFinal() {
        myFixture.configureByText("foo.kt", """
        class Foo {
            fun bar<caret>(){}
        }
        """)

        myFixture.launchAction(codeModifications.createChangeModifierAction(atCaret<UDeclaration>(myFixture), PsiModifier.FINAL, false)!!)
        myFixture.checkResult("""
        class Foo {
            open fun bar(){}
        }
        """)
    }

    fun testMakePrivate() {
        myFixture.configureByText("foo.kt", """
        class Foo<caret> {
            fun bar(){}
        }
        """)

        myFixture.launchAction(codeModifications.createChangeModifierAction(atCaret<UDeclaration>(myFixture), PsiModifier.PRIVATE, true)!!)
        myFixture.checkResult("""
        private class Foo {
            fun bar(){}
        }
        """)
    }

    fun testMakeNotPrivate() {
        myFixture.configureByText("foo.kt", """
        private class Foo<caret> {
            fun bar(){}
        }
        """.trim())

        myFixture.launchAction(codeModifications.createChangeModifierAction(atCaret<UDeclaration>(myFixture), PsiModifier.PRIVATE, false)!!)
        myFixture.checkResult("""
        class Foo {
            fun bar(){}
        }
        """.trim(), true)
    }

    fun testDontMakeFunInObjectsOpen() {
        myFixture.configureByText("foo.kt", """
        object Foo {
            fun bar<caret>(){}
        }
        """.trim())
        Assert.assertNull(codeModifications.createChangeModifierAction(atCaret<UDeclaration>(myFixture), PsiModifier.FINAL, false))
    }

    fun testAddVoidVoidMethod() {
        myFixture.configureByText("foo.kt", """
        |class Foo<caret> {
        |    fun bar() {}
        |}
        """.trim().trimMargin())

        myFixture.launchAction(codeModifications.createAddCallableMemberActions(NewCallableMemberInfo.simpleMethodInfo(
                atCaret<UClass>(myFixture), "baz", PsiModifier.PRIVATE, PsiType.VOID, emptyList())).first())
        myFixture.checkResult("""
        |class Foo {
        |    fun bar() {}
        |    private fun baz() {
        |
        |    }
        |}
        """.trim().trimMargin(), true)
    }

    fun testAddIntIntMethod() {
        myFixture.configureByText("foo.kt", """
        |class Foo<caret> {
        |    fun bar() {}
        |}
        """.trim().trimMargin())

        myFixture.launchAction(codeModifications.createAddCallableMemberActions(NewCallableMemberInfo.simpleMethodInfo(
                atCaret<UClass>(myFixture), "baz", PsiModifier.PUBLIC, PsiType.INT,paramsMaker(PsiType.INT).asList())).first())
        myFixture.checkResult("""
        |class Foo {
        |    fun bar() {}
        |    fun baz(param0: Int): Int {
        |
        |    }
        |}
        """.trim().trimMargin(), true)
    }

    fun testAddIntConstructor() {
        myFixture.configureByText("foo.kt", """
        |class Foo<caret> {
        |}
        """.trim().trimMargin())

        myFixture.launchAction(codeModifications.createAddCallableMemberActions(NewCallableMemberInfo.constructorInfo(
                atCaret<UClass>(myFixture), paramsMaker(PsiType.INT).asList())).first())
        myFixture.checkResult("""
        |class Foo {
        |    constructor(param0: Int) {
        |
        |    }
        |}
        """.trim().trimMargin(), true)
    }

    fun paramsMaker(vararg psyTypes: PsiType): Array<UParameter> {
        val f = KtPsiFactory(myFixture.project)
        val u = UastContext(myFixture.project)

        return JavaPsiFacade.getElementFactory(myFixture.project)
                .createParameterList(psyTypes.indices.map { "param$it" }.toTypedArray(),
                                     psyTypes).let {
            it.parameters
                    .map { u.convertElement(it, null, UParameter::class.java) as UParameter }
        }
                .toTypedArray()

//        val paramsString = psyTypes.mapIndexed { i, t -> "param$i: ${typeString(t)}" }.joinToString()
//
//        return (f.createClass("class A($paramsString)")
//                .let { u.convertElement(it, null, UClass::class.java) } as UClass).uastDeclarations.first().let { it as UMethod }
//                .uastParameters.toTypedArray()

//        return (f.createFunction("fun foo($paramsString)")
//                .originalElement as KtNamedFunction).let {
//            u.convertElement(it, null, UMethod::class.java)!! as UMethod
//        }.uastParameters.toTypedArray()
        //.parameters.map {it.toUElement(UParameter::class.java)!!}.toTypedArray()

//        return psyTypes.mapIndexed { index: Int, psiType: PsiType ->
//            f.createParameter("param$index: ${typeString(psiType)}").let {
//                u.convertElement(it, null, UParameter::class.java) as UParameter
////                KotlinUParameter(UastKotlinPsiParameter.create(it, it.parent), null)
//            }
//        }.toTypedArray()
    }

    fun testAddStringVarProperty() {
        myFixture.configureByText("foo.kt", """
        |class Foo<caret> {
        |    fun bar() {}
        |}
        """.trim().trimMargin())

        myFixture.launchAction(codeModifications.createAddBeanPropertyActions(
                atCaret<UClass>(myFixture), "baz", PsiModifier.PUBLIC, PsiType.getTypeByName("java.lang.String", project, GlobalSearchScope.allScope(project)), true, true)
                                       .withText("Add 'var' property 'baz' to 'Foo'"))
        myFixture.checkResult("""
        |class Foo {
        |    var baz: String = TODO("initialize me")
        |    fun bar() {}
        |}
        """.trim().trimMargin(), true)
    }

    fun testAddLateInitStringVarProperty() {
        myFixture.configureByText("foo.kt", """
        |class Foo<caret> {
        |    fun bar() {}
        |}
        """.trim().trimMargin())

        myFixture.launchAction(codeModifications.createAddBeanPropertyActions(
                atCaret<UClass>(myFixture), "baz", PsiModifier.PUBLIC, PsiType.getTypeByName("java.lang.String", project, GlobalSearchScope.allScope(project)), true, true)
                                       .withText("Add 'lateinit var' property 'baz' to 'Foo'"))
        myFixture.checkResult("""
        |class Foo {
        |    lateinit var baz: String
        |    fun bar() {}
        |}
        """.trim().trimMargin(), true)
    }

    fun testAddStringValProperty() {
        myFixture.configureByText("foo.kt", """
        |class Foo<caret> {
        |    fun bar() {}
        |}
        """.trim().trimMargin())

        myFixture.launchAction(codeModifications.createAddBeanPropertyActions(
                atCaret<UClass>(myFixture), "baz", PsiModifier.PUBLIC, PsiType.getTypeByName("java.lang.String", project, GlobalSearchScope.allScope(project)), false, true).first())
        myFixture.checkResult("""
        |class Foo {
        |    val baz: String = TODO("initialize me")
        |    fun bar() {}
        |}
        """.trim().trimMargin(), true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : UElement> atCaret(myFixture: CodeInsightTestFixture): T {
        return myFixture.elementAtCaret.toUElement() as T
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun Array<IntentionAction>.withText(text: String): IntentionAction =
            this.firstOrNull { it.text == text } ?:
            Assert.fail("intention with text '$text' was not found, only ${this.joinToString { "\"${it.text}\"" }} available") as Nothing

    private val codeModifications: JvmCommonIntentionActionsFactory
        get() = JvmCommonIntentionActionsFactory.forLanguage(Language.findLanguageByID("kotlin")!!)!!

}


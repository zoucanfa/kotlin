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

package org.jetbrains.kotlin.js.translate.expression

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.translate.context.InlineFunctionContext
import org.jetbrains.kotlin.js.translate.context.Namer
import org.jetbrains.kotlin.js.translate.context.TranslationContext

private val METADATA_PROPERTIES_COUNT = 2

class InlineMetadata(val tag: JsStringLiteral, val function: JsFunction, val callExpression: JsExpression) {
    companion object {
        @JvmStatic
        fun compose(function: JsFunction, descriptor: CallableDescriptor, context: TranslationContext): InlineMetadata {
            val tag = JsStringLiteral(Namer.getFunctionTag(descriptor, context.config))
            return InlineMetadata(tag, function, wrapInlineFunction(context.inlineFunctionContext!!, function))
        }

        private fun wrapInlineFunction(context: InlineFunctionContext, function: JsFunction): JsExpression {
            if (context.importBlock.isEmpty && context.declarationsBlock.isEmpty) return function

            val iif = JsFunction(function.scope, JsBlock(), "")
            iif.body.statements += context.importBlock.statements
            iif.body.statements += context.declarationsBlock.statements
            iif.body.statements += JsReturn(function)

            return JsInvocation(iif)
        }

        @JvmStatic
        fun decompose(expression: JsExpression?): InlineMetadata? =
                when (expression) {
                    is JsInvocation -> decomposeCreateFunctionCall(expression)
                    else -> null
                }

        private fun decomposeCreateFunctionCall(call: JsInvocation): InlineMetadata? {
            val qualifier = call.qualifier
            if (qualifier !is JsNameRef || qualifier.ident != Namer.DEFINE_INLINE_FUNCTION) return null

            val arguments = call.arguments
            if (arguments.size != METADATA_PROPERTIES_COUNT) return null

            val tag = arguments[0] as? JsStringLiteral
            val callExpression = arguments[1]
            val function = tryExtractFunction(callExpression)
            if (tag == null || function == null) return null

            return InlineMetadata(tag, function, callExpression)
        }

        @JvmStatic
        fun tryExtractFunction(callExpression: JsExpression): JsFunction? {
            return when (callExpression) {
                is JsInvocation -> {
                    val qualifier = callExpression.qualifier
                    if (callExpression.arguments.isNotEmpty() || qualifier !is JsFunction) return null
                    (qualifier.body.statements.lastOrNull() as? JsReturn)?.expression as? JsFunction
                }
                is JsFunction -> callExpression
                else -> null
            }
        }
    }

    val functionWithMetadata: JsExpression
        get() {
            val propertiesList = listOf(tag, callExpression)
            return JsInvocation(Namer.createInlineFunction(), propertiesList)
        }
}
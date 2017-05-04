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

package org.jetbrains.kotlin.codegen.inline

import org.jetbrains.kotlin.codegen.optimization.common.InsnSequence
import org.jetbrains.kotlin.codegen.optimization.common.asSequence
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.*

class DefaultLambdaInfo(val clazzInternalName: String, val args: Array<Type>, val instructionsToDelete: List<AbstractInsnNode>)

private data class Condition(
        val mask: Int, val constant: Int,
        val maskInstruction: VarInsnNode,
        val jumpInstruction: JumpInsnNode,
        val varInsNode: VarInsnNode?
) {
    val expandNotDelete = mask and constant != 0
    val varIndex = varInsNode?.`var` ?: 0
}

fun extractDefaultLambdaIndices(jvmSignature: JvmMethodSignature, functionDescriptor: FunctionDescriptor): Set<Int> {
    val valueParameters = jvmSignature.valueParameters
    val parameterOffsets = parameterOffsets(valueParameters)

    return functionDescriptor.valueParameters.filter {
        InlineUtil.isInlineLambdaParameter(it) && it.declaresDefaultValue()
    }.map {
        parameterOffsets[it.index]
    }.toSet()
}


fun expandMaskConditionsAndUpdateVariableNodes(
        node: MethodNode,
        maskStartIndex: Int,
        masks: List<Int>,
        methodHandlerIndex: Int,
        defaultLambdaIndices: Set<Int>
): List<DefaultLambdaInfo> {
    fun isMaskIndex(varIndex: Int): Boolean {
        return maskStartIndex <= varIndex && varIndex < maskStartIndex + masks.size
    }

    val maskProcessingHeader = node.instructions.asSequence().takeWhile {
        if (it is VarInsnNode) {
            if (isMaskIndex(it.`var`)) {
                /*if slot for default mask is updated than we occurred in actual function body*/
                return@takeWhile it.opcode == Opcodes.ILOAD
            }
            else if (methodHandlerIndex == it.`var`) {
                return@takeWhile it.opcode == Opcodes.ALOAD
            }
        }
        true
    }

    val conditions = maskProcessingHeader.filterIsInstance<VarInsnNode>().mapNotNull {
        if (isMaskIndex(it.`var`) &&
            it.next?.next?.opcode == Opcodes.IAND &&
            it.next.next.next?.opcode == Opcodes.IFEQ) {
            val jumpInstruction = it.next?.next?.next as JumpInsnNode
            Condition(
                    masks[it.`var` - maskStartIndex],
                    InlineCodegenUtil.getConstant(it.next),
                    it,
                    jumpInstruction,
                    jumpInstruction.label.previous as VarInsnNode
            )
        }
        else if (methodHandlerIndex == it.`var` &&
                 it.next?.opcode == Opcodes.IFNULL &&
                 it.next.next?.opcode == Opcodes.NEW) {
            //Always delete method handle for now
            //This logic should be updated when method handles would be supported
            Condition(0, 0, it, it.next as JumpInsnNode, null)
        }
        else null
    }.toList()

    val defaultLambdasInfo = extractDefaultLambdasInfo(conditions, defaultLambdaIndices)

    val indexToVarNode = node.localVariables?.filter { it.index < maskStartIndex }?.associateBy { it.index } ?: emptyMap()
    val toDelete = arrayListOf<AbstractInsnNode>()
    conditions.forEach {
        val jumpInstruction = it.jumpInstruction
        InsnSequence(it.maskInstruction, (if (it.expandNotDelete) jumpInstruction.next else jumpInstruction.label)).forEach {
            toDelete.add(it)
        }
        if (it.expandNotDelete) {
            indexToVarNode[it.varIndex]?.let { varNode ->
                varNode.start = it.jumpInstruction.label
            }
        }
    }

    toDelete.forEach {
        node.instructions.remove(it)
    }

    return defaultLambdasInfo
}



private fun extractDefaultLambdasInfo(conditions: List<Condition>, defaultLambdaIndices: Set<Int>): List<DefaultLambdaInfo> {
    val defaultLambdaConditions = conditions.filter { it.expandNotDelete && defaultLambdaIndices.contains(it.varIndex) }

    return defaultLambdaConditions.map {
        val instance = it.varInsNode!!.previous as MethodInsnNode
        when (instance) {
            is MethodInsnNode -> {
                assert(instance.name == "<init>") { "Expected constructor call for default lambda, but $instance" }
                val ownerInternalName = instance.owner
                val instanceCreation = InsnSequence(it.jumpInstruction, it.jumpInstruction.label).filter {
                    it.opcode == Opcodes.NEW && (it as TypeInsnNode).desc == ownerInternalName
                }.single()
                assert(instanceCreation.next?.opcode == Opcodes.DUP) {
                    "Dup should follow default lambda instance creation but ${instanceCreation.next}"
                }

                DefaultLambdaInfo(instance.owner, Type.getArgumentTypes(instance.desc), listOf(instanceCreation, instanceCreation.next, instance))
            }

            is FieldInsnNode -> DefaultLambdaInfo(instance.owner, emptyArray<Type>(), listOf(instance))
            else -> throw RuntimeException("Can't extract default lambda info $it. Unknown instruction: $instance")
        }
    }
}


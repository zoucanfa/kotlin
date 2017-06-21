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

@file:Suppress("unused")

package kotlin.script.dependencies

import java.io.File
import java.util.concurrent.CompletableFuture

// TODO_R: nullabilty is irritating
typealias Environment = Map<String, Any?>?

interface ScriptDependenciesResolver {
    fun resolve(contents: ScriptContents, environment: Environment): ScriptDependencyResult = emptySuccess()
}

interface StaticScriptDependenciesResolver : ScriptDependenciesResolver {
    fun resolve(environment: Environment): ScriptDependencyResult = emptySuccess()
}

// TODO_R: contents -> script or scriptContents
interface AsyncScriptDependenciesResolver : ScriptDependenciesResolver {
    fun resolveAsync(
            contents: ScriptContents, environment: Environment
    ): CompletableFuture<ScriptDependencyResult> = CompletableFuture.completedFuture(emptySuccess())

    override fun resolve(contents: ScriptContents, environment: Environment): ScriptDependencyResult
            = resolveAsync(contents, environment).get() ?: ScriptDependencyResult.Failure(ScriptReport("Async resolver returned null"))
}

private fun emptySuccess() = ScriptDependencyResult.Success(ScriptDependencies.Empty)

class EmptyDependenciesResolver : StaticScriptDependenciesResolver

interface ScriptContents {
    val file: File?
    val annotations: Iterable<Annotation>
    val text: CharSequence?
}

data class ScriptReport(val message: String, val severity: Severity = ScriptReport.Severity.ERROR, val position: Position? = null) {
    data class Position(val line: Int, val startColumn: Int, val endColumn: Int?)
    enum class Severity { ERROR, WARNING, INFO, DEBUG }
}

sealed class ScriptDependencyResult {
    abstract val dependencies: ScriptDependencies?
    abstract val reports: List<ScriptReport>

    class Success(
            override val dependencies: ScriptDependencies,
            override val reports: List<ScriptReport> = listOf()
    ) : ScriptDependencyResult()

    class Failure(override val reports: List<ScriptReport>) : ScriptDependencyResult() {
        constructor(vararg reports: ScriptReport): this(reports.asList())
        override val dependencies: ScriptDependencies? get() = null
    }
}
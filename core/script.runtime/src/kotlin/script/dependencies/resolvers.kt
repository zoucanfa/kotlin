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

typealias Environment = Map<String, Any?>?
typealias ErrorReporter = (ScriptDependenciesResolver.ReportSeverity, String, ScriptContents.Position?) -> Unit

interface ScriptDependenciesResolver {
    enum class ReportSeverity { ERROR, WARNING, INFO, DEBUG }
}

interface StaticScriptDependenciesResolver : ScriptDependenciesResolver {
    fun resolve(environment: Environment, onError: ErrorReporter): ScriptDependencies = ScriptDependencies.Empty
}

interface SyncDependenciesResolver : ScriptDependenciesResolver {
    fun resolve(contents: ScriptContents, environment: Environment, onError: ErrorReporter): ScriptDependencies = ScriptDependencies.Empty
}

interface AsyncScriptDependenciesResolver : ScriptDependenciesResolver {
    fun resolve(
            contents: ScriptContents, environment: Environment, onError: ErrorReporter, onDependenciesComputed: (ScriptDependencies) -> Unit
    ): Unit = onDependenciesComputed(ScriptDependencies.Empty)
}

class EmptyDependenciesResolver : StaticScriptDependenciesResolver

interface ScriptContents {

    data class Position(val line: Int, val col: Int)

    val file: File?
    val annotations: Iterable<Annotation>
    val text: CharSequence?
}
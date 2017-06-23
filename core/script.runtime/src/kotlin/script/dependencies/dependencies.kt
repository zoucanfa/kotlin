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
import java.util.Collections.emptyList

// TODO_R: open class?
interface ScriptDependencies {
    val javaHome: File? get() = null
    val classpath: List<File> get() = emptyList()
    val imports: List<String> get() = emptyList()
    val sources: List<File> get() = emptyList()
    val scripts: List<File> get() = emptyList()

    object Empty : ScriptDependencies
}
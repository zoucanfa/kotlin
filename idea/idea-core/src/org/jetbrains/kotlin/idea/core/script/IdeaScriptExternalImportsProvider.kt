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

package org.jetbrains.kotlin.idea.core.script

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.script.*
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.script.dependencies.KotlinScriptExternalDependencies

class IdeaScriptExternalImportsProvider(
        private val project: Project,
        private val scriptDefinitionProvider: KotlinScriptDefinitionProvider
) : KotlinScriptExternalImportsProvider {
    private val cacheLock = ReentrantReadWriteLock()
    private val cache = hashMapOf<String, KotlinScriptExternalDependencies?>()

    override fun <TF : Any> getExternalImports(file: TF): KotlinScriptExternalDependencies? = cacheLock.read {
        calculateExternalDependencies(file)
    }

    private fun <TF : Any> calculateExternalDependencies(file: TF): KotlinScriptExternalDependencies? {
        val path = getFilePath(file)
        val cached = cache[path]
        return if (cached != null) cached
        else {
            val scriptDef = scriptDefinitionProvider.findScriptDefinition(file)
            if (scriptDef != null) {
                val deps = scriptDef.getDependenciesFor(file, project, null)
                cacheLock.write {
                    cache.put(path, deps)
                }
                deps
            }
            else null
        }
    }

    // optimized for initial caching, additional handling of possible duplicates to save a call to distinct
    // returns list of cached files
    override fun <TF : Any> cacheExternalImports(files: Iterable<TF>): Iterable<TF> = cacheLock.write {
        return files.mapNotNull { file ->
            scriptDefinitionProvider.findScriptDefinition(file)?.let {
                cacheForFile(file, getFilePath(file), it)
            }
        }
    }

    private fun <TF : Any> cacheForFile(file: TF, path: String, scriptDef: KotlinScriptDefinition): TF? {
        if (!isValidFile(file) || cache.containsKey(path)) return null

        val deps = scriptDef.getDependenciesFor(file, project, null)
        cache.put(path, deps)

        return file.takeIf { deps != null }
    }

    // optimized for update, no special duplicates handling
    // returns files with valid script definition (or deleted from cache - which in fact should have script def too)
    // TODO: this is the badly designed contract, since it mixes the entities, but these files are needed on the calling site now. Find out other solution
    override fun <TF : Any> updateExternalImportsCache(files: Iterable<TF>): Iterable<TF> = cacheLock.write {
        return files.mapNotNull { file ->
            scriptDefinitionProvider.findScriptDefinition(file)?.let {
                updateForFile(file, it)
            }
        }
    }

    private fun <TF : Any> updateForFile(file: TF, scriptDef: KotlinScriptDefinition): TF? {
        val path = getFilePath(file)
        if (!isValidFile(file)) {
            return file.takeIf { cache.remove(path) != null }
        }

        val oldDeps = cache[path]
        val deps = scriptDef.getDependenciesFor(file, project, oldDeps)
        return when {
            deps != null && (oldDeps == null ||
                             !deps.classpath.isSamePathListAs(oldDeps.classpath) || !deps.sources.isSamePathListAs(oldDeps.sources)) -> {
                // changed or new
                cache.put(path, deps)
                file
            }
            deps != null -> {
                // same as before
                null
            }
            cache.remove(path) != null -> {
                file
            }
            else -> null // unknown
        }
    }

    override fun invalidateCaches() {
        cacheLock.write(cache::clear)
    }

    override fun getKnownCombinedClasspath(): List<File> = cacheLock.read {
        cache.values.flatMap { it?.classpath ?: emptyList() }
    }.distinct()

    override fun getKnownSourceRoots(): List<File> = cacheLock.read {
        cache.values.flatMap { it?.sources ?: emptyList() }
    }.distinct()

    override fun <TF : Any> getCombinedClasspathFor(files: Iterable<TF>) = error("Not called in IDE")
}

private fun Iterable<File>.isSamePathListAs(other: Iterable<File>): Boolean =
        with (Pair(iterator(), other.iterator())) {
            while (first.hasNext() && second.hasNext()) {
                if (first.next().canonicalPath != second.next().canonicalPath) return false
            }
            !(first.hasNext() || second.hasNext())
        }
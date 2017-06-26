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

package org.jetbrains.kotlin.incremental

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.compilerRunner.MessageCollectorToOutputItemsCollectorAdapter
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.js.incremental.IncrementalDataProvider
import org.jetbrains.kotlin.js.incremental.IncrementalDataProviderImpl
import org.jetbrains.kotlin.js.incremental.IncrementalResultsConsumer
import org.jetbrains.kotlin.js.incremental.IncrementalResultsConsumerImpl
import java.io.File
import java.util.ArrayList
import java.util.HashSet


fun makeJsIncrementally(
        cachesDir: File,
        sourceRoots: Iterable<File>,
        args: K2JSCompilerArguments,
        messageCollector: MessageCollector = MessageCollector.NONE,
        reporter: ICReporter = EmptyICReporter
) {
    val versions = commonCacheVersions(cachesDir) + jsCacheVersion(cachesDir)
    val allKotlinFiles = sourceRoots.asSequence().flatMap { it.walk() }
            .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }.toList()

    withJsIC {
        val compiler = IncrementalJsCompilerRunner(cachesDir, versions, reporter)
        compiler.compile(allKotlinFiles, args, messageCollector) {
            it.inputsCache.sourceSnapshotMap.compareAndUpdate(allKotlinFiles)
        }
    }
}

inline fun <R> withJsIC(fn: ()->R): R {
    val isJsEnabledBackup = IncrementalCompilation.isEnabledForJs()
    IncrementalCompilation.setIsEnabledForJs(true)

    try {
        return withIC { fn() }
    }
    finally {
        IncrementalCompilation.setIsEnabledForJs(isJsEnabledBackup)
    }
}

class IncrementalJsCompilerRunner(
        workingDir: File,
        private val cacheVersions: List<CacheVersion>,
        private val reporter: ICReporter
) {
    private val cacheDirectory = File(workingDir, CACHES_DIR_NAME)

    fun compile(
            allKotlinSources: List<File>,
            args: K2JSCompilerArguments,
            messageCollector: MessageCollector,
            changedFiles: (IncrementalJsCachesManager)-> ChangedFiles
    ): ExitCode {
        var caches = IncrementalJsCachesManager(cacheDirectory, reporter)

        fun onError(e: Exception): ExitCode {
            caches.clean()
            // todo: warn?
            reporter.report { "Possible cache corruption. Rebuilding. $e" }
            caches = IncrementalJsCachesManager(cacheDirectory, reporter)
            return compileIncrementally(args, caches, allKotlinSources, CompilationMode.Rebuild, messageCollector)
        }

        return try {
            val compilationMode = calculateSourcesToCompile(caches, changedFiles(caches), args)
            val exitCode = compileIncrementally(args, caches, allKotlinSources, compilationMode, messageCollector)

            if (!caches.close(flush = true)) throw RuntimeException("Could not flush caches")

            return exitCode
        }
        catch (e: Exception) {
            onError(e)
        }
    }

    private sealed class CompilationMode {
        class Incremental(val dirtyFiles: Set<File>) : CompilationMode()
        object Rebuild : CompilationMode()
    }

    private fun calculateSourcesToCompile(
            caches: IncrementalJsCachesManager,
            changedFiles: ChangedFiles,
            args: K2JSCompilerArguments
    ): CompilationMode {
        fun rebuild(reason: ()->String): CompilationMode {
            reporter.report { "Non-incremental compilation will be performed: ${reason()}" }
            caches.clean()
            //dirtySourcesSinceLastTimeFile.delete()
            args.outputFile?.let { File(it).delete() }
            return CompilationMode.Rebuild
        }

        if (changedFiles !is ChangedFiles.Known) return rebuild { "inputs' changes are unknown (first or clean build)" }

        val dirtyFiles = HashSet<File>(with(changedFiles) { modified.size + removed.size })
        with(changedFiles) {
            modified.asSequence() + removed.asSequence()
        }.forEach { if (it.isKotlinFile()) dirtyFiles.add(it) }

        return CompilationMode.Incremental(dirtyFiles)
    }

    private fun compileIncrementally(
            args: K2JSCompilerArguments,
            caches: IncrementalJsCachesManager,
            allKotlinSources: List<File>,
            compilationMode: CompilationMode,
            messageCollector: MessageCollector
    ): ExitCode {
        assert(IncrementalCompilation.isEnabledForJs()) { "Incremental compilation is not enabled" }

        @Suppress("NAME_SHADOWING")
        var compilationMode = compilationMode
        var dirtySources: MutableList<File>

        when (compilationMode) {
            is CompilationMode.Incremental -> {
                dirtySources = ArrayList(compilationMode.dirtyFiles)
            }
            is CompilationMode.Rebuild -> {
                dirtySources = allKotlinSources.toMutableList()
            }
        }.run {} // run is added to force exhaustive when

        val allSourcesToCompile = HashSet<File>()
        args.freeArgs.addAll(allKotlinSources.map { it.absolutePath })

        var exitCode = ExitCode.OK
        while (dirtySources.any()) {
            val (sourcesToCompile, _) = dirtySources.partition(File::exists)
            allSourcesToCompile.addAll(sourcesToCompile)

            // todo: val text = allSourcesToCompile.map { it.canonicalPath }.joinToString(separator = System.getProperty("line.separator"))
            // todo: dirtySourcesSinceLastTimeFile.writeText(text)
            val metadataHeaderFile = File(cacheDirectory, "header.metadata")

            val services = Services.Builder()
            if (compilationMode is CompilationMode.Incremental) {
                val translationResults = caches.jsCache.translationResults
                dirtySources.forEach { translationResults.remove(it) }
                val headerMetada = metadataHeaderFile.readBytes()
                val packagePartsMetadata = arrayListOf<ByteArray>()
                val binaryTrees = arrayListOf<ByteArray>()

                translationResults.values().forEach {
                    packagePartsMetadata.add(it.metadata)
                    binaryTrees.add(it.binaryAst)
                }

                val incrementalDataProvider = IncrementalDataProviderImpl(
                        headerMetada,
                        packagePartsMetadata = packagePartsMetadata,
                        binaryTrees = binaryTrees)
                services.register(IncrementalDataProvider::class.java, incrementalDataProvider)
            }
            else {
                caches.clean()
            }

            val incrementalResults = IncrementalResultsConsumerImpl()
            services.register(IncrementalResultsConsumer::class.java, incrementalResults)

            exitCode = compileChanged(sourcesToCompile.toSet(), args, messageCollector, services.build())
            if (exitCode != ExitCode.OK) break

            //dirtySourcesSinceLastTimeFile.delete()
            metadataHeaderFile.writeBytes(incrementalResults.headerMetadata!!)

            var hasChanges = false
            incrementalResults.packageParts.forEach {
                hasChanges = hasChanges || caches.jsCache.translationResults.put(it.sourceFile, metadata = it.proto, binaryAst = it.binaryAst)
            }

            if (compilationMode is CompilationMode.Rebuild || !hasChanges) break

            compilationMode = CompilationMode.Rebuild
            dirtySources = allKotlinSources.toMutableList()
        }

        if (exitCode == ExitCode.OK) {
            cacheVersions.forEach { it.saveIfNeeded() }
        }

        return exitCode
    }

    private fun compileChanged(
            sourcesToCompile: Set<File>,
            args: K2JSCompilerArguments,
            messageCollector: MessageCollector,
            services: Services
    ): ExitCode {
        val compiler = K2JSCompiler()

        args.reportOutputFiles = true
        val outputItemCollector = OutputItemsCollectorImpl()
        @Suppress("NAME_SHADOWING")
        val messageCollector = MessageCollectorToOutputItemsCollectorAdapter(messageCollector, outputItemCollector)

        //reporter.report { "compiling with args: ${ArgumentUtils.convertArgumentsToStringList(args)}" }
        //reporter.report { "compiling with classpath: ${classpath.toList().sorted().joinToString()}" }

        return compiler.exec(messageCollector, services, args).also { exitCode ->
            reporter.reportCompileIteration(sourcesToCompile, exitCode)
        }
    }

    companion object {
        const val CACHES_DIR_NAME = "caches-js"
    }
}
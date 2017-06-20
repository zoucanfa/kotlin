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

package org.jetbrains.kotlin.jps.build

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.containers.StringInterner
import org.jetbrains.kotlin.TestWithWorkingDir
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compilerRunner.*
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.components.LookupInfo
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.incremental.makeModuleFile
import org.jetbrains.kotlin.incremental.utils.TestMessageCollector
import org.jetbrains.kotlin.preloading.ClassCondition
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.utils.PathUtil
import org.junit.Assert
import org.junit.Test
import java.io.*
import java.util.*

private val DECLARATION_KEYWORDS = listOf("interface", "class", "enum class", "object", "fun", "operator fun", "val", "var")
private val DECLARATION_STARTS_WITH = DECLARATION_KEYWORDS.map { it + " " }

abstract class AbstractLookupTrackerTest : TestWithWorkingDir() {
    // ignore KDoc like comments which starts with `/**`, example: /** text */
    private val COMMENT_WITH_LOOKUP_INFO = "/\\*[^*]+\\*/".toRegex()

    private fun File.kotlinFiles() =
            walk().filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }

    fun doTest(path: String) {
        val srcDir = File(path)
        val processedSrcDir = File(workingDir, "src").apply { mkdirs() }
        val outDir = File(workingDir, "out").apply { mkdirs() }

        val fileMapping = srcDir.kotlinFiles()
                .associate { it to File(processedSrcDir, it.toRelativeString(srcDir)) }

        for ((ktFile, processedKtFile) in fileMapping) {
            processedKtFile.parentFile.mkdirs()
            val codeWithoutComments = ktFile.readText().replace(COMMENT_WITH_LOOKUP_INFO, "")
            processedKtFile.writeText(codeWithoutComments)
        }

        val lookupTracker = TestLookupTracker()
        compileWithLookupTracker(processedSrcDir, outDir, lookupTracker)
        checkLookups(lookupTracker, fileMapping)
    }

    private fun compileWithLookupTracker(srcDir: File, outDir: File, lookupTracker: TestLookupTracker) {
        val paths = PathUtil.getKotlinPathsForDistDirectory()
        val services = Services.Builder().run {
            register(LookupTracker::class.java, lookupTracker)
            build()
        }
        val classesToLoadByParent = ClassCondition { className ->
            className.startsWith("org.jetbrains.kotlin.incremental.components.")
            || className == "org.jetbrains.kotlin.config.Services"
            || className == "org.jetbrains.kotlin.cli.common.ExitCode"
        }

        val messageCollector = TestMessageCollector()
        val outputItemsCollector = OutputItemsCollectorImpl()
        val env = JpsCompilerEnvironment(paths, services, classesToLoadByParent, messageCollector, outputItemsCollector)

        val stream = ByteArrayOutputStream()
        val out = PrintStream(stream)

        val rc = runCompiler(srcDir, outDir, env, out)

        val reader = BufferedReader(StringReader(stream.toString()))
        CompilerOutputParser.parseCompilerMessagesFromReader(messageCollector, reader, outputItemsCollector)
        val actualOutput = (listOf(rc?.toString()) + messageCollector.errors).joinToString("\n")

        Assert.assertEquals("Unexpected compiler output", "OK", actualOutput)
    }

    private fun runCompiler(srcDir: File, outDir: File, env: JpsCompilerEnvironment, out: PrintStream): Any? {
        val module = makeModuleFile("lookup-tracker-test", true,
                                    outDir,
                                    srcDir.kotlinFiles().toList(),
                                    listOf(srcDir),
                                    emptyList(),
                                    emptyList())
        val args = arrayOf("-module", module.canonicalPath)
        try {
            return CompilerRunnerUtil.invokeExecMethod(K2JVMCompiler::class.java.name, args, env, out)
        }
        finally {
            module.delete()
        }
    }

    private fun checkLookups(lookupTracker: TestLookupTracker, fileMapping: Map<File, File>) {
        val fileToLookups = lookupTracker.lookups.groupBy { it.filePath }

        fun checkLookupsInFile(expectedFile: File, actualFile: File) {
            val independentFilePath = FileUtil.toSystemIndependentName(actualFile.path)
            val lookupsFromFile = fileToLookups[independentFilePath] ?: return

            val text = actualFile.readText()

            val matchResult = COMMENT_WITH_LOOKUP_INFO.find(text)
            if (matchResult != null) {
                throw AssertionError("File $actualFile contains multiline comment in range ${matchResult.range}")
            }

            val lines = text.lines().toMutableList()

            for ((line, lookupsFromLine) in lookupsFromFile.groupBy { it.position.line }) {
                val columnToLookups = lookupsFromLine.groupBy { it.position.column }.toList().sortedBy { it.first }

                val lineContent = lines[line - 1]
                val parts = ArrayList<CharSequence>(columnToLookups.size * 2)

                var start = 0

                for ((column, lookupsFromColumn) in columnToLookups) {
                    val end = column - 1
                    parts.add(lineContent.subSequence(start, end))

                    val lookups = lookupsFromColumn.distinct().joinToString(separator = " ", prefix = "/*", postfix = "*/") {
                        val rest = lineContent.substring(end)

                        val name =
                                when {
                                    rest.startsWith(it.name) || // same name
                                    rest.startsWith("$" + it.name) || // backing field
                                    DECLARATION_STARTS_WITH.any { rest.startsWith(it) } // it's declaration
                                         -> ""
                                    else -> "(" + it.name + ")"
                                }

                        it.scopeKind.toString()[0].toLowerCase().toString() + ":" + it.scopeFqName.let { if (it.isNotEmpty()) it else "<root>" } + name
                    }

                    parts.add(lookups)

                    start = end
                }

                lines[line - 1] = parts.joinToString("") + lineContent.subSequence(start, lineContent.length)
            }

            val actual = lines.joinToString("\n")

            KotlinTestUtils.assertEqualsToFile(expectedFile, actual)
        }

        for ((ktFile, processedKtFile) in fileMapping) {
            checkLookupsInFile(ktFile, processedKtFile)
        }
    }
}

class TestLookupTracker : LookupTracker {
    val lookups = arrayListOf<LookupInfo>()
    private val interner = StringInterner()

    override val requiresPosition: Boolean
        get() = true

    override fun record(filePath: String, position: Position, scopeFqName: String, scopeKind: ScopeKind, name: String) {
        val internedFilePath = interner.intern(filePath)
        val internedScopeFqName = interner.intern(scopeFqName)
        val internedName = interner.intern(name)

        lookups.add(LookupInfo(internedFilePath, position, internedScopeFqName, scopeKind, internedName))
    }
}



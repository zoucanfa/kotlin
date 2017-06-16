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

package org.jetbrains.kotlin.idea.run.scratch

import com.intellij.compiler.options.CompileStepBeforeRun
import com.intellij.execution.scratch.JavaScratchCompilationSupport.getScratchOutputDirectory
import com.intellij.execution.scratch.JavaScratchCompilationSupport.getScratchTempDirectory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.compiler.*
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.io.IOException
import java.util.*

class KtScratchCompilationSupport(project: Project, compileManager: CompilerManager) : ProjectComponent, CompileTask {

    init {
        compileManager.addAfterTask(this)
    }

    override fun execute(context: CompileContext): Boolean {
        val project = context.project

        val configuration = CompileStepBeforeRun.getRunConfiguration(context) as? KtScratchConfiguration ?: return true
        val scratchConfig = configuration
        val scratchUrl = scratchConfig.scratchFileUrl
        if (scratchUrl == null) {
            context.addMessage(CompilerMessageCategory.ERROR, "Associated scratch file not found", null, -1, -1)
            return false
        }
        val module = scratchConfig.configurationModule.module

        val targetSdk = findSdk(module, context, scratchUrl) ?: return true

        val outputDir = getScratchOutputDirectory(project) ?: return true // should not happen for normal projects
        FileUtil.delete(outputDir) // perform cleanup

        try {
            val scratchFile = File(VirtualFileManager.extractPath(scratchUrl))
            var srcFile = scratchFile
            if (!StringUtil.endsWith(srcFile.name, ".java")) {

                val srcDir = getScratchTempDirectory(project) ?:
                             return true // should not happen for normal projects
                FileUtil.delete(srcDir) // perform cleanup

                val srcFileName = inventSourceFileName(scratchUrl, project, scratchFile)
                srcFile = File(srcDir, srcFileName + ".java")
                FileUtil.copy(scratchFile, srcFile)
            }

            val (cp, platformCp) = computeClasspath(module, project)

            compileFiles(targetSdk, project, platformCp, cp, setOf(srcFile), outputDir)
        }
        catch (e: CompilationException) {
            for (m in e.messages) {
                context.addMessage(m.category, m.text, scratchUrl, m.line, m.column)
            }
        }
        catch (e: IOException) {
            context.addMessage(CompilerMessageCategory.ERROR, e.message, scratchUrl, -1, -1)
        }

        return true
    }

    private fun computeClasspath(module: Module?, project: Project): Pair<Collection<File>, Collection<File>> {
        val cp = LinkedHashSet<File>()
        val platformCp = ArrayList<File>()

        val orderEnumerator = if (module != null) {
            ModuleRootManager.getInstance(module).orderEntries()
        }
        else {
            ProjectRootManager.getInstance(project).orderEntries()
        }

        ApplicationManager.getApplication().runReadAction {
            for (s in orderEnumerator.compileOnly().recursively().exportedOnly().withoutSdk().pathsList.pathList) {
                cp.add(File(s))
            }
            for (s in orderEnumerator.compileOnly().sdkOnly().pathsList.pathList) {
                platformCp.add(File(s))
            }
        }
        return Pair(cp, platformCp)
    }

    private fun compileFiles(targetSdk: Sdk, project: Project?, platformCp: Collection<File>, cp: Collection<File>, files: Set<File>, outputDir: File) {
        val pathsForIdeaPlugin = PathUtil.getKotlinPathsForIdeaPlugin()
        val options = ArrayList<String>()
        options.add("-g") // always compile with debug info
        val sdkVersion = JavaSdk.getInstance().getVersion(targetSdk)
        if (sdkVersion != null) {
            val langLevel = "1." + Integer.valueOf(3 + sdkVersion.maxLanguageLevel.ordinal)!!
            options.add("-source")
            options.add(langLevel)
            options.add("-target")
            options.add(langLevel)
        }
        options.add("-proc:none") // disable annotation processing

        val result = CompilerManager.getInstance(project).compileJavaCode(
                options, platformCp, cp, emptyList<File>(), emptyList<File>(), files, outputDir
        )
        for (classObject in result) {
            val bytes = classObject.content
            if (bytes != null) {
                FileUtil.writeToFile(File(classObject.path), bytes)
            }
        }
    }

    private fun findSdk(module: Module?, context: CompileContext, scratchUrl: String): Sdk? {
        val project = context.project
        val targetSdk = if (module != null) ModuleRootManager.getInstance(module).sdk else ProjectRootManager.getInstance(project).projectSdk
        if (targetSdk == null) {
            val message = if (module != null)
                "Cannot find associated SDK for run configuration module \"" + module.name + "\".\nPlease check project settings."
            else
                "Cannot find associated project SDK for the run configuration.\nPlease check project settings."
            context.addMessage(CompilerMessageCategory.ERROR, message, scratchUrl, -1, -1)
            return null
        }
        if (targetSdk.sdkType !is JavaSdkType) {
            val message = if (module != null)
                "Expected Java SDK for run configuration module \"" + module.name + "\".\nPlease check project settings."
            else
                "Expected Java SDK for project \"" + project.name + "\".\nPlease check project settings."
            context.addMessage(CompilerMessageCategory.ERROR, message, scratchUrl, -1, -1)
            return null
        }

        return targetSdk
    }

    private fun inventSourceFileName(scratchUrl: String, project: Project, scratchFile: File): String? {
        val srcFileName = runReadAction {
            val vFile = VirtualFileManager.getInstance().findFileByUrl(scratchUrl)
            if (vFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(vFile)
                if (psiFile is KtFile) {
                    return@runReadAction "ktScratch1"
                }
            }
            FileUtil.getNameWithoutExtension(scratchFile)
        }
        return srcFileName
    }

    override fun projectOpened() {}

    override fun projectClosed() {}

    override fun initComponent() {}

    override fun disposeComponent() {}

    override fun getComponentName(): String {
        return "KtScratchCompilationSupport"
    }
}
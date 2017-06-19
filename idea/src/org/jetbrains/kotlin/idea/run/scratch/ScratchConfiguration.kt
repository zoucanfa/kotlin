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

import com.intellij.execution.*
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.application.AbstractApplicationConfigurationProducer
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configuration.ConfigurationFactoryEx
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.scratch.JavaScratchConfigurable
import com.intellij.execution.util.JavaParametersUtil
import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileType
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.psi.PsiElement
import com.intellij.ui.LayeredIcon
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.run.KotlinRunConfigurationProducer
import org.jetbrains.kotlin.idea.run.KotlinRunConfigurationProducer.Companion.getEntryPointContainer

// TODO_R: review and document duplication
class KtScratchConfiguration(
        name: String?, project: Project?
) : ApplicationConfiguration(name, project, KtScratchConfigurationFactory) {
    var SCRATCH_FILE_ID: Int = 0

    override fun checkConfiguration() {
        JavaParametersUtil.checkAlternativeJRE(this)
        val className = MAIN_CLASS_NAME
        if (className.isNullOrEmpty()) {
            throw RuntimeConfigurationError(ExecutionBundle.message("no.main.class.specified.error.text"))
        }
        if (SCRATCH_FILE_ID <= 0) {
            throw RuntimeConfigurationError("No scratch file associated with configuration")
        }
        if (scratchVirtualFile == null) {
            throw RuntimeConfigurationError("Associated scratch file not found")
        }
        ProgramParametersUtil.checkWorkingDirectoryExist(this, project, configurationModule.module)
        JavaRunConfigurationExtensionManager.checkConfigurationIsValid(this)
    }

    @Throws(ExecutionException::class)
    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState? {
//        val state = object : ApplicationConfiguration.JavaApplicationCommandLineState<JavaScratchConfiguration>(this, env) {
//            @Throws(ExecutionException::class)
//            override fun setupJavaParameters(params: JavaParameters) {
//                super.setupJavaParameters(params)
//                val scrachesOutput = JavaScratchCompilationSupport.getScratchOutputDirectory(project)
//                if (scrachesOutput != null) {
//                    params.classPath.addFirst(FileUtil.toCanonicalPath(scrachesOutput.absolutePath).replace('/', File.separatorChar))
//                }
//            }
//
//            @Throws(ExecutionException::class)
//            override fun startProcess(): OSProcessHandler {
//                val handler = super.startProcess()
//                if (runnerSettings is DebuggingRunnerData) {
//                    val vFile = configuration.scratchVirtualFile
//                    if (vFile != null) {
//                        DebuggerManager.getInstance(project).addDebugProcessListener(handler, object : DebugProcessListener {
//                            override fun processAttached(process: DebugProcess?) {
//                                if (vFile.isValid) {
//                                    process!!.appendPositionManager(JavaScratchPositionManager(process as DebugProcessImpl?, vFile))
//                                }
//                                process!!.removeDebugProcessListener(this)
//                            }
//                        })
//                    }
//                }
//                return handler
//            }
//        }
//        state.consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project, configurationModule.searchScope)
//        return state
        return null
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return JavaScratchConfigurable(project)
    }

    override fun isCompileBeforeLaunchAddedByDefault(): Boolean {
        return true
    }

    val scratchFileUrl get() = scratchVirtualFile?.url

    val scratchVirtualFile: VirtualFile?
        get() {
            val id = SCRATCH_FILE_ID
            if (id <= 0) {
                return null
            }
            return ManagingFS.getInstance().findFileById(id)
        }
}

object KtScratchConfigurationType : ApplicationConfigurationType() {
    override fun getId() = "Kotlin Scratch"
    override fun getDisplayName() = "Kotlin Scratch"
    override fun getConfigurationTypeDescription() = "Configuration for kotlin scratch files"

    override fun getIcon() = LayeredIcon.create(super.getIcon(), AllIcons.Actions.Scratch)
    override fun getConfigurationFactories() = arrayOf(KtScratchConfigurationFactory)
}

object KtScratchConfigurationFactory : ConfigurationFactoryEx<KtScratchConfiguration>(KtScratchConfigurationType) {
    override fun isApplicable(project: Project) = false
    override fun createTemplateConfiguration(project: Project) = KtScratchConfiguration("", project)
    override fun onNewConfigurationCreated(configuration: KtScratchConfiguration) = configuration.onNewConfigurationCreated()
}


class KtScratchConfigurationProducer : AbstractApplicationConfigurationProducer<KtScratchConfiguration>(KtScratchConfigurationType) {

    override fun setupConfigurationFromContext(
            configuration: KtScratchConfiguration,
            context: ConfigurationContext,
            sourceElement: Ref<PsiElement>?
    ): Boolean {
        // TODO_R: dumb mode?
        // TODO_R: unify?

        val location = context.location ?: return false
        val vFile = location.virtualFile
        if (vFile !is VirtualFileWithId || vFile.fileType !== ScratchFileType.INSTANCE) return false

        val psiFile = location.psiElement.containingFile
        if (psiFile == null || psiFile.language != KotlinLanguage.INSTANCE) return false

        configuration.SCRATCH_FILE_ID = vFile.id

        val entryPointContainer = getEntryPointContainer(location.psiElement, checkIsInProject = false) ?: return false
        configuration.setMainClassName(KotlinRunConfigurationProducer.getStartClassFqName(entryPointContainer))
        configuration.setGeneratedName()
        return true
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        return other.isProducedBy(AbstractApplicationConfigurationProducer::class.java)
               && !other.isProducedBy(KtScratchConfigurationProducer::class.java)
    }

    override fun isConfigurationFromContext(configuration: KtScratchConfiguration, context: ConfigurationContext): Boolean {
        val location = context.psiLocation
        val aClass = ApplicationConfigurationType.getMainClass(location) ?: return false
        if (!Comparing.equal(JavaExecutionUtil.getRuntimeQualifiedName(aClass), configuration.MAIN_CLASS_NAME)) return false

        // for scratches it is enough to check that the configuration is associated with the same scratch file
        val scratchFile = configuration.scratchVirtualFile ?: return false

        val containingFile = aClass.containingFile ?: return false
        if (scratchFile != containingFile.virtualFile) return false

        return true
    }
}
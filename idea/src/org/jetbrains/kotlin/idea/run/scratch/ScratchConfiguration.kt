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

import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configuration.ConfigurationFactoryEx
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.scratch.JavaScratchConfigurable
import com.intellij.execution.scratch.JavaScratchConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.LayeredIcon

class KtScratchConfiguration(
        name: String?, project: Project?
) : JavaScratchConfiguration(name, project, KtScratchConfigurationFactory) {
    // TODO_R: ?
    override fun checkConfiguration() = super.checkConfiguration()

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        // TODO_R:
        return JavaScratchConfigurable(project)
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
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

package org.jetbrains.kotlin.javac.wrappers.trees

import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import com.sun.tools.javac.tree.JCTree
import org.jetbrains.kotlin.javac.JavacWrapper
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.JavaClassifier
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class TreePathResolverCache(private val javac: JavacWrapper) {

    private val cache = hashMapOf<Tree, JavaClassifier?>()

    fun resolve(treePath: TreePath): JavaClassifier? = with (treePath) {
        if (cache.containsKey(leaf)) return cache[leaf]

        return tryToResolve().apply { cache[leaf] = this }
    }

    private fun TreePath.tryToResolve(): JavaClassifier? {
        val pathSegments = leaf.toString()
                .substringBefore("<")
                .substringAfter("@")
                .split(".")

        with (compilationUnit as JCTree.JCCompilationUnit) {
            tryToResolveInner(pathSegments)?.let { return it }
            tryToResolvePackageClass(pathSegments)?.let { return it }
            tryToResolveByFqName(pathSegments)?.let { return it }
            tryToResolveSingleTypeImport(pathSegments)?.let { return it }
            tryToResolveTypeImportOnDemand(pathSegments)?.let { return it }
            tryToResolveInJavaLang(pathSegments)?.let { return it }
        }

        return tryToResolveTypeParameter()
    }

    fun tryToResolveInJavaLang(pathSegments: List<String>): JavaClass? {
        val classId = classId("java.lang", pathSegments.first())
        var javaClass = findJavaOrKotlinClass(classId) ?: return null

        for (it in pathSegments.drop(1)) {
            javaClass = javaClass.findInner(Name.identifier(it)) ?: return null
        }

        return javaClass
    }

    private fun JCTree.JCCompilationUnit.tryToResolveTypeImportOnDemand(pathSegments: List<String>): JavaClass? {
        imports.filter { it.qualifiedIdentifier.toString().endsWith("*") }
                .forEach { import ->
                    val fqName = "${import.qualifiedIdentifier.toString().dropLast(1)}${pathSegments.joinToString(separator = ".")}"
                    tryToResolveByFqName(fqName.split("."))
                            ?.let { return it }
                }

        return null
    }

    private fun JCTree.JCCompilationUnit.tryToResolveSingleTypeImport(pathSegments: List<String>): JavaClass? {
        imports.filter { it.qualifiedIdentifier.toString().endsWith(".${pathSegments.first()}") }
                .forEach { import ->
                    val fqName = pathSegments.drop(1).let {
                        if (it.isEmpty()) {
                            import.qualifiedIdentifier.toString()
                        }
                        else {
                            import.qualifiedIdentifier.toString() + "." + pathSegments.drop(1).joinToString(separator = ".")
                        }
                    }
                    tryToResolveByFqName(fqName.split("."))
                            ?.let { return it }
                }

        return null
    }

    fun tryToResolveByFqName(pathSegments: List<String>): JavaClass? {
        classId(className = pathSegments.joinToString(separator = "."))
                .let { findJavaOrKotlinClass(it) }
                ?.let { return it }

        fun tryToResolveByFqName(packageSegments: List<String>, classSegments: List<String>): JavaClass? {
            val javaClass = classId(packageSegments.joinToString(separator = "."), classSegments.first())
                    .let { findJavaOrKotlinClass(it) } ?: return null

            return classSegments.drop(1).let {
                if (it.isNotEmpty()) {
                    javaClass.findInner(it)
                }
                else {
                    javaClass
                }
            }
        }

        val packageSegments = arrayListOf<String>()

        pathSegments.forEachIndexed { i, _ ->
            packageSegments.add(pathSegments[i])
            if (i == pathSegments.lastIndex) return null

            tryToResolveByFqName(packageSegments, pathSegments.drop(i + 1))?.let { return it }
        }

        return null
    }

    private fun JCTree.JCCompilationUnit.tryToResolvePackageClass(pathSegments: List<String>): JavaClass? {
        val classId = classId(packageName?.toString() ?: "<root>", pathSegments.first())
        val javaClass = findJavaOrKotlinClass(classId) ?: return null

        return pathSegments.drop(1).let {
            if (it.isNotEmpty()) {
                javaClass.findInner(it)
            }
            else {
                javaClass
            }
        }
    }

    private fun TreePath.tryToResolveInner(pathSegments: List<String>): JavaClass? =
            enclosingClasses.forEach { javaClass ->
                javaClass.findInner(pathSegments)?.let { return it }
            }.let { return null }

    private fun JavaClass.findInner(pathSegments: List<String>): JavaClass? {
        var javaClass = findInner(Name.identifier(pathSegments.first())) ?: return null

        for (pathSegment in pathSegments.drop(1)) {
            javaClass = javaClass.findInner(Name.identifier(pathSegment)) ?: return null
        }

        return javaClass
    }

    private fun JavaClass.findInner(name: Name): JavaClass? {
        findInnerClass(name)?.let { return it }

        supertypes.mapNotNull { it.classifier as? JavaClass }
                .forEach { javaClass -> javaClass.findInner(name)?.let { return it } }

        return null
    }

    private fun TreePath.tryToResolveTypeParameter() =
            flatMap {
                when (it) {
                    is JCTree.JCClassDecl -> it.typarams
                    is JCTree.JCMethodDecl -> it.typarams
                    else -> emptyList<JCTree.JCTypeParameter>()
                }
            }
                    .find { it.toString().substringBefore(" ") == leaf.toString() }
                    ?.let {
                        TreeBasedTypeParameter(it,
                                               javac.getTreePath(it, compilationUnit),
                                               javac)
                    }

    private val TreePath.enclosingClasses: List<JavaClass>
        get() {
            val outerClasses = filterIsInstance<JCTree.JCClassDecl>()
                    .dropWhile { it.extending == leaf || leaf in it.implementing }
                    .asReversed()
                    .map { it.simpleName.toString() }

            val packageName = compilationUnit.packageName?.toString() ?: "<root>"
            val outermostClassName = outerClasses.firstOrNull() ?: return emptyList()

            val outermostClassId = classId(packageName, outermostClassName)
            var outermostClass = javac.findClass(outermostClassId) ?: return emptyList()

            val classes = arrayListOf<JavaClass>().apply { add(outermostClass) }

            for (it in outerClasses.drop(1)) {
                outermostClass = outermostClass.findInnerClass(Name.identifier(it)) ?: return classes
                classes.add(outermostClass)
            }

            return classes
        }

    private fun findJavaOrKotlinClass(classId: ClassId) = javac.findClass(classId) ?: javac.getKotlinClassifier(classId)

}

fun classId(packageName: String = "<root>", className: String) =
        if (packageName != "<root>")
            ClassId(FqName(packageName), Name.identifier(className))
        else
            ClassId.topLevel(FqName(className))
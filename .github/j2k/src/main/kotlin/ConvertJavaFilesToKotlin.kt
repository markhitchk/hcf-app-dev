package me.eugeniomarletti

import com.intellij.codeInsight.ContainerProvider
import com.intellij.codeInsight.NullabilityAnnotationInfo
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInsight.runner.JavaMainMethodProvider
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.lang.MetaLanguage
import com.intellij.lang.jvm.facade.JvmElementProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.fileTypes.FileTypeExtensionPoint
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.FileContextProvider
import com.intellij.psi.JavaModuleSystem
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.augment.TypeAnnotationModifier
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.impl.JavaClassSupersImpl
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import com.intellij.psi.meta.MetaDataContributor
import com.intellij.psi.stubs.BinaryFileStubBuilders
import com.intellij.psi.util.JavaClassSupers
import org.jetbrains.kotlin.j2k.JavaToKotlinTranslator
import org.jetbrains.kotlin.j2k.translateToKotlin
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.net.URLClassLoader

fun main(args: Array<String>) {
    require(args.size == 1) { "usage: converter <source-root>" }
    val root = File(args[0])
    val javaSources = root.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .sortedBy { it.absolutePath }
        .toList()
    require(javaSources.isNotEmpty()) { "No Java files found under $root" }
    println("Converting ${javaSources.size} Java files")
    val converted = convertJavaFilesToKotlin(javaSources)
    for ((javaSource, kotlinSource) in converted) {
        val target = File(javaSource.parentFile, javaSource.nameWithoutExtension + ".kt")
        require(!target.exists()) { "Target already exists: $target" }
        target.writeText(kotlinSource)
        check(javaSource.delete()) { "Could not delete $javaSource" }
        println("Converted ${javaSource.name} -> ${target.name}")
    }
}

fun convertJavaFilesToKotlin(javaSources: List<File>): Map<File, String> =
    when {
        javaSources.isEmpty() -> emptyMap()
        else -> {
            val disposable = Disposer.newDisposable()
            try {
                val javaCoreEnvironment = setUpJavaCoreEnvironment(disposable)
                javaSources.associateWith { javaSource ->
                    val fileContents = FileUtil.loadFile(javaSource, true)
                    translateToKotlin(fileContents, javaCoreEnvironment.project)
                }
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

private fun setUpJavaCoreEnvironment(disposable: Disposable): JavaCoreProjectEnvironment {
    Extensions.cleanRootArea(disposable)
    registerExtensionPoints(Extensions.getRootArea())

    val applicationEnvironment = JavaCoreApplicationEnvironment(disposable)
    val javaCoreEnvironment = object : JavaCoreProjectEnvironment(disposable, applicationEnvironment) {
        override fun preregisterServices() {
            val projectArea = Extensions.getArea(project)
            @Suppress("DEPRECATION")
            CoreApplicationEnvironment.registerExtensionPoint(
                projectArea,
                PsiTreeChangePreprocessor.EP_NAME,
                PsiTreeChangePreprocessor::class.java
            )
            CoreApplicationEnvironment.registerExtensionPoint(
                projectArea,
                PsiElementFinder.EP_NAME,
                PsiElementFinder::class.java
            )
            CoreApplicationEnvironment.registerExtensionPoint(
                projectArea,
                JvmElementProvider.EP_NAME,
                JvmElementProvider::class.java
            )
        }
    }

    javaCoreEnvironment.project.registerService(
        NullableNotNullManager::class.java,
        object : NullableNotNullManager(javaCoreEnvironment.project) {
            override fun isNullable(owner: PsiModifierListOwner, checkBases: Boolean) =
                !isNotNull(owner, checkBases)

            override fun isNotNull(owner: PsiModifierListOwner, checkBases: Boolean) = true
            override fun hasHardcodedContracts(element: PsiElement): Boolean = false
            override fun getNullables() = emptyList<String>()
            override fun setNullables(vararg p0: String) = Unit
            override fun getNotNulls() = emptyList<String>()
            override fun setNotNulls(vararg p0: String) = Unit
            override fun getDefaultNullable() = ""
            override fun setDefaultNullable(defaultNullable: String) = Unit
            override fun getDefaultNotNull() = ""
            override fun setDefaultNotNull(p0: String) = Unit
            override fun setInstrumentedNotNulls(p0: List<String>) = Unit
            override fun getInstrumentedNotNulls() = emptyList<String>()
            override fun isJsr305Default(
                psiAnnotation: PsiAnnotation,
                p1: Array<PsiAnnotation.TargetType>
            ): NullabilityAnnotationInfo? = null
        }
    )

    applicationEnvironment.application.registerService(
        JavaClassSupers::class.java,
        JavaClassSupersImpl::class.java
    )

    PathUtil.getJdkClassesRootsFromCurrentJre().forEach(javaCoreEnvironment::addJarToClassPath)
    findAnnotations()?.takeIf { it.exists() }?.let(javaCoreEnvironment::addJarToClassPath)
    return javaCoreEnvironment
}

private fun registerExtensionPoints(area: ExtensionsArea) {
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        BinaryFileStubBuilders.EP_NAME,
        FileTypeExtensionPoint::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        FileContextProvider.EP_NAME,
        FileContextProvider::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        MetaDataContributor.EP_NAME,
        MetaDataContributor::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        PsiAugmentProvider.EP_NAME,
        PsiAugmentProvider::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        JavaMainMethodProvider.EP_NAME,
        JavaMainMethodProvider::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        ContainerProvider.EP_NAME,
        ContainerProvider::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        ClassFileDecompilers.EP_NAME,
        ClassFileDecompilers.Decompiler::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        TypeAnnotationModifier.EP_NAME,
        TypeAnnotationModifier::class.java
    )
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        MetaLanguage.EP_NAME,
        MetaLanguage::class.java
    )
    @Suppress("UnstableApiUsage")
    CoreApplicationEnvironment.registerExtensionPoint(
        area,
        JavaModuleSystem.EP_NAME,
        JavaModuleSystem::class.java
    )
}

private fun findAnnotations(): File? =
    JavaToKotlinTranslator::class.java.classLoader
        .let { generateSequence(it, ClassLoader::getParent) }
        .filterIsInstance<URLClassLoader>()
        .flatMap { it.urLs.asSequence() }
        .filter { it.protocol == "file" }
        .map { it.file }
        .filter { it.endsWith("/annotations.jar") }
        .map(::File)
        .firstOrNull()

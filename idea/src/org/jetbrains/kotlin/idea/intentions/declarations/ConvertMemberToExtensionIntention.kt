/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.intentions.declarations

import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.SmartList
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.unsafeResolveToDescriptor
import org.jetbrains.kotlin.idea.core.*
import org.jetbrains.kotlin.idea.intentions.SelfTargetingRangeIntention
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.getReturnTypeReference
import org.jetbrains.kotlin.idea.refactoring.withExpectedActuals
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.util.ImportInsertHelper
import org.jetbrains.kotlin.idea.util.actualDeclarations
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.idea.util.expectedDeclaration
import org.jetbrains.kotlin.idea.util.isEffectivelyExpect
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.utils.addIfNotNull

private val LOG = Logger.getInstance(ConvertMemberToExtensionIntention::class.java)

class ConvertMemberToExtensionIntention : SelfTargetingRangeIntention<KtCallableDeclaration>(
    KtCallableDeclaration::class.java,
    KotlinBundle.lazyMessage("convert.member.to.extension")
), LowPriorityAction {
    private fun isApplicable(element: KtCallableDeclaration): Boolean {
        val classBody = element.parent as? KtClassBody ?: return false
        if (classBody.parent !is KtClass) return false
        if (element.receiverTypeReference != null) return false
        if (element.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
        when (element) {
            is KtProperty -> if (element.hasInitializer()) return false
            is KtSecondaryConstructor -> return false
        }

        return true
    }

    override fun applicabilityRange(element: KtCallableDeclaration): TextRange? {
        if (!element.withExpectedActuals().all { it is KtCallableDeclaration && isApplicable(it) }) return null
        return (element.nameIdentifier ?: return null).textRange
    }

    override fun startInWriteAction() = false

    //TODO: local class

    override fun applyTo(element: KtCallableDeclaration, editor: Editor?) {
        var allowExpected = true

        element.expectedDeclaration()?.actualDeclarations()?.let {
            if (it.isEmpty()) {
                allowExpected = askIfExpectedIsAllowed(element.containingKtFile)
            }
        }

        runWriteAction {
            val (extension, bodyTypeToSelect) = createExtensionCallableAndPrepareBodyToSelect(element, allowExpected)

            editor?.apply {
                unblockDocument()

                if (extension.isValid) {

                    if (bodyTypeToSelect != GeneratedBodyType.NOTHING) {
                        val bodyToSelect = getBodyForSelection(extension, bodyTypeToSelect)

                        if (bodyToSelect != null) {
                            val range = bodyToSelect.textRange
                            moveCaret(range.startOffset, ScrollType.CENTER)

                            val parent = bodyToSelect.parent
                            val lastSibling =
                                if (parent is KtBlockExpression)
                                    parent.rBrace?.siblings(forward = false, withItself = false)?.first { it !is PsiWhiteSpace }
                                else
                                    bodyToSelect.siblings(forward = true, withItself = false).lastOrNull()
                            val endOffset = lastSibling?.endOffset ?: range.endOffset
                            selectionModel.setSelection(range.startOffset, endOffset)
                        } else {
                            LOG.error("Extension created with new method body for $bodyToSelect but this body was not found after document commit. Extension text: \"${extension.text}\"")
                            moveCaret(extension.textOffset, ScrollType.CENTER)
                        }
                    } else {
                        moveCaret(extension.textOffset, ScrollType.CENTER)
                    }
                } else {
                    LOG.error("Extension invalidated during document commit. Extension text \"${extension.text}\"")
                }
            }
        }
    }

    private fun getBodyForSelection(extension: KtCallableDeclaration, bodyTypeToSelect: GeneratedBodyType): KtExpression? {
        fun selectBody(declaration: KtDeclarationWithBody): KtExpression? {

            if (!declaration.hasBody()) return extension

            return declaration.bodyExpression?.let {
                (it as? KtBlockExpression)?.statements?.singleOrNull() ?: it
            }
        }

        return when (bodyTypeToSelect) {
            GeneratedBodyType.FUNCTION -> (extension as? KtFunction)?.let { selectBody(it) }
            GeneratedBodyType.GETTER -> (extension as? KtProperty)?.getter?.let { selectBody(it) }
            GeneratedBodyType.SETTER -> (extension as? KtProperty)?.setter?.let { selectBody(it) }
            else -> null
        }
    }

    private enum class GeneratedBodyType {
        NOTHING,
        FUNCTION,
        SETTER,
        GETTER
    }

    private fun processSingleDeclaration(
        element: KtCallableDeclaration,
        allowExpected: Boolean
    ): Pair<KtCallableDeclaration, GeneratedBodyType> {
        val descriptor = element.unsafeResolveToDescriptor()
        val containingClass = descriptor.containingDeclaration as ClassDescriptor

        val isEffectivelyExpected = allowExpected && element.isEffectivelyExpect()

        val file = element.containingKtFile
        val project = file.project
        val outermostParent = KtPsiUtil.getOutermostParent(element, file, false)

        val ktFilesToAddImports = LinkedHashSet<KtFile>()
        val javaCallsToFix = SmartList<PsiMethodCallExpression>()
        for (ref in ReferencesSearch.search(element)) {
            when (ref) {
                is KtReference -> {
                    val refFile = ref.element.containingKtFile
                    if (refFile != file) {
                        ktFilesToAddImports.add(refFile)
                    }
                }
                is PsiReferenceExpression -> javaCallsToFix.addIfNotNull(ref.parent as? PsiMethodCallExpression)
            }
        }

        val typeParameterList = newTypeParameterList(element)

        val psiFactory = KtPsiFactory(element)

        val extension = file.addAfter(element, outermostParent) as KtCallableDeclaration
        file.addAfter(psiFactory.createNewLine(), outermostParent)
        file.addAfter(psiFactory.createNewLine(), outermostParent)
        element.delete()

        extension.setReceiverType(containingClass.defaultType)

        if (typeParameterList != null) {
            if (extension.typeParameterList != null) {
                extension.typeParameterList!!.replace(typeParameterList)
            } else {
                extension.addBefore(typeParameterList, extension.receiverTypeReference)
                extension.addBefore(psiFactory.createWhiteSpace(), extension.receiverTypeReference)
            }
        }

        extension.modifierList?.getModifier(KtTokens.PROTECTED_KEYWORD)?.delete()
        extension.modifierList?.getModifier(KtTokens.ABSTRACT_KEYWORD)?.delete()
        extension.modifierList?.getModifier(KtTokens.OPEN_KEYWORD)?.delete()
        extension.modifierList?.getModifier(KtTokens.FINAL_KEYWORD)?.delete()

        if (isEffectivelyExpected && !extension.hasExpectModifier()) {
            extension.addModifier(KtTokens.EXPECT_KEYWORD)
        }

        var bodyTypeToSelect = GeneratedBodyType.NOTHING

        val bodyText = getFunctionBodyTextFromTemplate(
            project,
            if (extension is KtFunction) TemplateKind.FUNCTION else TemplateKind.PROPERTY_INITIALIZER,
            extension.name,
            extension.getReturnTypeReference()?.text ?: "Unit",
            extension.containingClassOrObject?.fqName
        )

        when (extension) {
            is KtFunction -> {
                if (!extension.hasBody() && !isEffectivelyExpected) {
                    //TODO: methods in PSI for setBody
                    extension.add(psiFactory.createBlock(bodyText))
                    bodyTypeToSelect = GeneratedBodyType.FUNCTION
                }
            }

            is KtProperty -> {
                val templateProperty = psiFactory.createDeclaration<KtProperty>("var v: Any\nget()=$bodyText\nset(value){\n$bodyText\n}")

                if (!isEffectivelyExpected) {
                    val templateGetter = templateProperty.getter!!
                    val templateSetter = templateProperty.setter!!

                    var getter = extension.getter
                    if (getter == null) {
                        getter = extension.addAfter(templateGetter, extension.typeReference) as KtPropertyAccessor
                        extension.addBefore(psiFactory.createNewLine(), getter)
                        bodyTypeToSelect = GeneratedBodyType.GETTER
                    } else if (!getter.hasBody()) {
                        getter = getter.replace(templateGetter) as KtPropertyAccessor
                        bodyTypeToSelect = GeneratedBodyType.GETTER
                    }

                    if (extension.isVar) {
                        var setter = extension.setter
                        if (setter == null) {
                            setter = extension.addAfter(templateSetter, getter) as KtPropertyAccessor
                            extension.addBefore(psiFactory.createNewLine(), setter)
                            if (bodyTypeToSelect == GeneratedBodyType.NOTHING) {
                                bodyTypeToSelect = GeneratedBodyType.SETTER
                            }
                        } else if (!setter.hasBody()) {
                            setter.replace(templateSetter) as KtPropertyAccessor
                            if (bodyTypeToSelect == GeneratedBodyType.NOTHING) {
                                bodyTypeToSelect = GeneratedBodyType.SETTER
                            }
                        }
                    }
                }
            }
        }

        if (ktFilesToAddImports.isNotEmpty()) {
            val newDescriptor = extension.unsafeResolveToDescriptor()
            val importInsertHelper = ImportInsertHelper.getInstance(project)
            for (ktFileToAddImport in ktFilesToAddImports) {
                importInsertHelper.importDescriptor(ktFileToAddImport, newDescriptor)
            }
        }

        if (javaCallsToFix.isNotEmpty()) {
            val lightMethod = extension.toLightMethods().first()
            for (javaCallToFix in javaCallsToFix) {
                javaCallToFix.methodExpression.qualifierExpression?.let {
                    val argumentList = javaCallToFix.argumentList
                    argumentList.addBefore(it, argumentList.expressions.firstOrNull())
                }

                val newRef = javaCallToFix.methodExpression.bindToElement(lightMethod)
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(newRef)
            }
        }

        return extension to bodyTypeToSelect
    }

    private fun askIfExpectedIsAllowed(file: KtFile): Boolean {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            return file.allChildren.any { it is PsiComment && it.text.trim() == "// ALLOW_EXPECT_WITHOUT_ACTUAL" }
        }

        return Messages.showYesNoDialog(
            KotlinBundle.message("do.you.want.to.make.new.extension.an.expected.declaration"),
            text,
            Messages.getQuestionIcon()
        ) == Messages.YES
    }

    private fun createExtensionCallableAndPrepareBodyToSelect(
        element: KtCallableDeclaration,
        allowExpected: Boolean = true
    ): Pair<KtCallableDeclaration, GeneratedBodyType> {
        val expectedDeclaration = element.expectedDeclaration() as? KtCallableDeclaration
        if (expectedDeclaration != null) {
            element.withExpectedActuals().filterIsInstance<KtCallableDeclaration>().forEach {
                if (it != element) {
                    processSingleDeclaration(it, allowExpected)
                }
            }
        }

        val classVisibility = element.containingClass()?.visibilityModifierType()
        val (extension, bodyTypeToSelect) = processSingleDeclaration(element, allowExpected)
        if (classVisibility != null && extension.visibilityModifier() == null) {
            extension.addModifier(classVisibility)
        }

        return extension to bodyTypeToSelect
    }

    private fun newTypeParameterList(member: KtCallableDeclaration): KtTypeParameterList? {
        val classElement = member.parent.parent as KtClass
        val classParams = classElement.typeParameters
        if (classParams.isEmpty()) return null
        val allTypeParameters = classParams + member.typeParameters
        val text = allTypeParameters.joinToString(",", "<", ">") { it.text }
        return KtPsiFactory(member).createDeclaration<KtFunction>("fun $text foo()").typeParameterList
    }

    companion object {
        fun convert(element: KtCallableDeclaration): KtCallableDeclaration =
            ConvertMemberToExtensionIntention().createExtensionCallableAndPrepareBodyToSelect(element).first
    }
}
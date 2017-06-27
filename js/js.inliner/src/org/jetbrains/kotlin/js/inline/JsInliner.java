/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.js.inline;

import com.intellij.psi.PsiElement;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.backend.common.CommonCoroutineCodegenUtilKt;
import org.jetbrains.kotlin.descriptors.CallableDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.FunctionDescriptor;
import org.jetbrains.kotlin.diagnostics.DiagnosticSink;
import org.jetbrains.kotlin.diagnostics.Errors;
import org.jetbrains.kotlin.js.backend.JsToStringGenerationVisitor;
import org.jetbrains.kotlin.js.backend.ast.*;
import org.jetbrains.kotlin.js.backend.ast.metadata.MetadataProperties;
import org.jetbrains.kotlin.js.config.JsConfig;
import org.jetbrains.kotlin.js.inline.clean.FunctionPostProcessor;
import org.jetbrains.kotlin.js.inline.clean.RemoveUnusedFunctionDefinitionsKt;
import org.jetbrains.kotlin.js.inline.clean.RemoveUnusedLocalFunctionDeclarationsKt;
import org.jetbrains.kotlin.js.inline.context.FunctionContext;
import org.jetbrains.kotlin.js.inline.context.InliningContext;
import org.jetbrains.kotlin.js.inline.context.NamingContext;
import org.jetbrains.kotlin.js.inline.util.*;
import org.jetbrains.kotlin.resolve.inline.InlineStrategy;

import java.util.*;

import static org.jetbrains.kotlin.js.translate.utils.JsAstUtils.flattenStatement;
import static org.jetbrains.kotlin.js.translate.utils.JsAstUtils.pureFqn;

public class JsInliner extends JsVisitorWithContextImpl {

    private final JsConfig config;
    private final Map<JsName, FunctionWithWrapper> functions;
    private final Map<String, FunctionWithWrapper> accessors;
    private final Stack<JsInliningContext> inliningContexts = new Stack<>();
    private final Set<JsFunction> processedFunctions = CollectionUtilsKt.IdentitySet();
    private final Set<JsFunction> inProcessFunctions = CollectionUtilsKt.IdentitySet();
    private final FunctionReader functionReader;
    private final DiagnosticSink trace;
    private final Map<String, JsName> existingImports = new HashMap<>();

    // these are needed for error reporting, when inliner detects cycle
    private final Stack<JsFunction> namedFunctionsStack = new Stack<>();
    private final LinkedList<JsCallInfo> inlineCallInfos = new LinkedList<>();
    private final Function1<JsNode, Boolean> canBeExtractedByInliner =
            node -> node instanceof JsInvocation && hasToBeInlined((JsInvocation) node);

    public static void process(
            @NotNull JsConfig.Reporter reporter,
            @NotNull JsConfig config,
            @NotNull DiagnosticSink trace,
            @NotNull JsName currentModuleName,
            @NotNull List<JsProgramFragment> fragments,
            @NotNull List<JsProgramFragment> fragmentsToProcess,
            @NotNull List<JsStatement> importStatements
    ) {
        Map<JsName, FunctionWithWrapper> functions = CollectUtilsKt.collectNamedFunctionsAndWrappers(fragments);
        Map<String, FunctionWithWrapper> accessors = CollectUtilsKt.collectAccessors(fragments);
        DummyAccessorInvocationTransformer accessorInvocationTransformer = new DummyAccessorInvocationTransformer();
        for (JsProgramFragment fragment : fragmentsToProcess) {
            accessorInvocationTransformer.accept(fragment.getDeclarationBlock());
            accessorInvocationTransformer.accept(fragment.getInitializerBlock());
        }
        FunctionReader functionReader = new FunctionReader(reporter, config, currentModuleName, fragments);
        JsInliner inliner = new JsInliner(config, functions, accessors, functionReader, trace);
        List<JsNode> nodesToPostProcess = new ArrayList<>();

        for (JsStatement statement : importStatements) {
            inliner.processImportStatement(statement);
        }

        for (JsProgramFragment fragment : fragmentsToProcess) {
            inliner.inliningContexts.push(inliner.new JsInliningContext(inliner.new ListContext<JsStatement>()));
            inliner.acceptStatement(fragment.getDeclarationBlock());

            // There can be inlined function in top-level initializers, we need to optimize them as well
            JsFunction fakeInitFunction = new JsFunction(JsDynamicScope.INSTANCE, fragment.getInitializerBlock(), "");
            inliner.accept(new JsBlock(new JsExpressionStatement(fakeInitFunction)));

            inliner.inliningContexts.pop();
            JsBlock block = new JsBlock(fragment.getDeclarationBlock(), fragment.getInitializerBlock(), fragment.getExportBlock());
            nodesToPostProcess.add(block);
        }

        Map<JsName, JsFunction> jsFunctions = new HashMap<>();
        for (Map.Entry<JsName, FunctionWithWrapper> entry : functions.entrySet()) {
            jsFunctions.put(entry.getKey(), entry.getValue().getFunction());
        }
        RemoveUnusedFunctionDefinitionsKt.removeUnusedFunctionDefinitions(nodesToPostProcess, jsFunctions);
    }

    private JsInliner(
            @NotNull JsConfig config,
            @NotNull Map<JsName, FunctionWithWrapper> functions,
            @NotNull Map<String, FunctionWithWrapper> accessors,
            @NotNull FunctionReader functionReader,
            @NotNull DiagnosticSink trace
    ) {
        this.config = config;
        this.functions = functions;
        this.accessors = accessors;
        this.functionReader = functionReader;
        this.trace = trace;
    }

    private void processImportStatement(JsStatement statement) {
        if (statement instanceof JsVars) {
            JsVars jsVars = (JsVars) statement;
            String tag = getImportTag(jsVars);
            if (tag != null) {
                existingImports.put(tag, jsVars.getVars().get(0).getName());
            }
        }
    }

    @Nullable
    private static String getImportTag(JsVars jsVars) {
        if (jsVars.getVars().size() == 1) {
            JsVars.JsVar jsVar = jsVars.getVars().get(0);
            if (jsVar.getInitExpression() != null) {
                return extractImportTag(jsVar.getInitExpression());
            }
        }

        return null;
    }

    @Override
    public boolean visit(@NotNull JsFunction function, @NotNull JsContext context) {
        inliningContexts.push(new JsInliningContext(getLastStatementLevelContext()));
        assert !inProcessFunctions.contains(function): "Inliner has revisited function";
        inProcessFunctions.add(function);

        if (functions.values().stream().anyMatch(namedFunction -> namedFunction.getFunction().equals(function))) {
            namedFunctionsStack.push(function);
        }

        return super.visit(function, context);
    }

    @Override
    public void endVisit(@NotNull JsFunction function, @NotNull JsContext context) {
        super.endVisit(function, context);
        NamingUtilsKt.refreshLabelNames(function.getBody(), function.getScope());

        RemoveUnusedLocalFunctionDeclarationsKt.removeUnusedLocalFunctionDeclarations(function);
        processedFunctions.add(function);

        new FunctionPostProcessor(function).apply();

        assert inProcessFunctions.contains(function);
        inProcessFunctions.remove(function);

        inliningContexts.pop();

        if (!namedFunctionsStack.empty() && namedFunctionsStack.peek() == function) {
            namedFunctionsStack.pop();
        }
    }

    @Override
    public boolean visit(@NotNull JsInvocation call, @NotNull JsContext context) {
        if (!hasToBeInlined(call)) return true;

        JsFunction containingFunction = getCurrentNamedFunction();

        if (containingFunction != null) {
            inlineCallInfos.add(new JsCallInfo(call, containingFunction));
        }

        JsFunction definition = getFunctionContext().getFunctionDefinition(call).getFunction();

        if (inProcessFunctions.contains(definition))  {
            reportInlineCycle(call, definition);
        }
        else if (!processedFunctions.contains(definition)) {
            accept(definition);
        }

        return true;
    }

    @Override
    public void endVisit(@NotNull JsInvocation x, @NotNull JsContext ctx) {
        if (hasToBeInlined(x)) {
            inline(x, ctx);
        }

        JsCallInfo lastCallInfo = null;

        if (!inlineCallInfos.isEmpty()) {
            lastCallInfo = inlineCallInfos.getLast();
        }

        if (lastCallInfo != null && lastCallInfo.call == x) {
            inlineCallInfos.removeLast();
        }
    }

    @Override
    protected void doAcceptStatementList(List<JsStatement> statements) {
        // at top level of js ast, contexts stack can be empty,
        // but there is no inline calls anyway
        if(!inliningContexts.isEmpty()) {
            int i = 0;

            while (i < statements.size()) {
                List<JsStatement> additionalStatements =
                        ExpressionDecomposer.preserveEvaluationOrder(statements.get(i), canBeExtractedByInliner);
                statements.addAll(i, additionalStatements);
                i += additionalStatements.size() + 1;
            }
        }

        super.doAcceptStatementList(statements);
    }

    private void inline(@NotNull JsInvocation call, @NotNull JsContext context) {
        DeclarationDescriptor callDescriptor = MetadataProperties.getDescriptor(call);
        if (isSuspendWithCurrentContinuation(callDescriptor)) {
            inlineSuspendWithCurrentContinuation(call, context);
            return;
        }

        JsInliningContext inliningContext = getInliningContext();
        InlineableResult inlineableResult = getInlineableCallReplacement(call, inliningContext);

        JsStatement inlineableBody = inlineableResult.getInlineableBody();
        JsExpression resultExpression = inlineableResult.getResultExpression();
        JsContext<JsStatement> statementContext = inliningContext.getStatementContext();
        // body of inline function can contain call to lambdas that need to be inlined
        JsStatement inlineableBodyWithLambdasInlined = accept(inlineableBody);
        assert inlineableBody == inlineableBodyWithLambdasInlined;
        statementContext.addPrevious(flattenStatement(inlineableBody));

        /*
         * Assumes, that resultExpression == null, when result is not needed.
         * @see FunctionInlineMutator.isResultNeeded()
         */
        if (resultExpression == null) {
            statementContext.removeMe();
            return;
        }

        resultExpression = accept(resultExpression);
        MetadataProperties.setSynthetic(resultExpression, true);
        context.replaceMe(resultExpression);
    }

    private InlineableResult getInlineableCallReplacement(@NotNull JsInvocation call, @NotNull JsInliningContext inliningContext) {
        FunctionInlineMutator mutator = new FunctionInlineMutator(call, inliningContext);

        if (mutator.getWrapper() != null) {
            applyWrapper(mutator.getWrapper(), mutator, inliningContext);
        }

        mutator.process();

        JsStatement inlineableBody = mutator.getBody();
        JsLabel breakLabel = mutator.getBreakLabel();
        if (breakLabel != null) {
            breakLabel.setStatement(inlineableBody);
            inlineableBody = breakLabel;
        }

        return new InlineableResult(inlineableBody, mutator.getResultExpr());
    }

    private void applyWrapper(@NotNull JsBlock wrapper, @NotNull FunctionInlineMutator mutator, @NotNull InliningContext inliningContext) {
        Map<JsName, JsExpression> replacements = new HashMap<>();
        for (JsStatement statement : wrapper.getStatements()) {
            if (!(statement instanceof JsReturn)) {
                for (JsName name : CollectUtilsKt.collectDefinedNamesInAllScopes(statement)) {
                    if (name.isTemporary() && !replacements.containsKey(name)) {
                        JsName alias = JsScope.declareTemporaryName(name.getIdent());
                        alias.copyMetadataFrom(name);
                        JsExpression replacement = alias.makeRef();
                        replacements.put(name, replacement);
                        mutator.getNamingContext().replaceName(name, replacement);
                    }
                }
            }
        }

        for (JsStatement statement : wrapper.getStatements()) {
            if (statement instanceof JsReturn) continue;

            statement = statement.deepCopy();
            RewriteUtilsKt.replaceNames(statement, replacements);

            if (statement instanceof JsVars) {
                JsVars jsVars = (JsVars) statement;
                String tag = getImportTag(jsVars);
                if (tag != null) {
                    JsName name = jsVars.getVars().get(0).getName();
                    JsName existingName = MetadataProperties.getLocalAlias(name);
                    if (existingName == null) {
                        existingName = existingImports.computeIfAbsent(tag, t -> {
                            inliningContext.getStatementContextBeforeCurrentFunction().addPrevious(jsVars.deepCopy());
                            return name;
                        });
                    }

                    if (name != existingName) {
                        mutator.getNamingContext().replaceName(name, pureFqn(existingName, null));
                    }

                    continue;
                }
            }

            inliningContext.getStatementContextBeforeCurrentFunction().addPrevious(statement);
        }
    }

    private static boolean isSuspendWithCurrentContinuation(@Nullable DeclarationDescriptor descriptor) {
        if (!(descriptor instanceof FunctionDescriptor)) return false;
        return CommonCoroutineCodegenUtilKt.isBuiltInSuspendCoroutineOrReturn((FunctionDescriptor) descriptor.getOriginal());
    }

    private void inlineSuspendWithCurrentContinuation(@NotNull JsInvocation call, @NotNull JsContext context) {
        JsExpression lambda = call.getArguments().get(0);
        JsExpression continuationArg = call.getArguments().get(call.getArguments().size() - 1);

        JsInvocation invocation = new JsInvocation(lambda, continuationArg);
        MetadataProperties.setSuspend(invocation, true);
        context.replaceMe(accept(invocation));
    }

    @NotNull
    private JsInliningContext getInliningContext() {
        return inliningContexts.peek();
    }

    @NotNull
    private FunctionContext getFunctionContext() {
        return getInliningContext().getFunctionContext();
    }

    @Nullable
    private JsFunction getCurrentNamedFunction() {
        if (namedFunctionsStack.empty()) return null;
        return namedFunctionsStack.peek();
    }

    @Nullable
    private static String extractImportTag(@NotNull JsExpression expression) {
        StringBuilder sb = new StringBuilder();
        return extractImportTagImpl(expression, sb) ? sb.toString() : null;
    }

    private static boolean extractImportTagImpl(@NotNull JsExpression expression, @NotNull StringBuilder sb) {
        if (expression instanceof JsNameRef) {
            JsNameRef nameRef = (JsNameRef) expression;
            if (nameRef.getQualifier() != null) {
                if (!extractImportTagImpl(nameRef.getQualifier(), sb)) {
                    return false;
                }
            }
            else {
                sb.append('.');
            }
            sb.append(JsToStringGenerationVisitor.javaScriptString(nameRef.getIdent()));
            return true;
        }
        else if (expression instanceof JsArrayAccess) {
            JsArrayAccess arrayAccess = (JsArrayAccess) expression;
            if (!extractImportTagImpl(arrayAccess.getArrayExpression(), sb)) {
                return false;
            }
            sb.append(".");
            if (!extractImportTagImpl(arrayAccess.getIndexExpression(), sb)) {
                return false;
            }
            return true;
        }
        else {
            return false;
        }
    }

    private void reportInlineCycle(@NotNull JsInvocation call, @NotNull JsFunction calledFunction) {
        MetadataProperties.setInlineStrategy(call, InlineStrategy.NOT_INLINE);
        Iterator<JsCallInfo> it = inlineCallInfos.descendingIterator();

        while (it.hasNext()) {
            JsCallInfo callInfo = it.next();
            PsiElement psiElement = MetadataProperties.getPsiElement(callInfo.call);

            CallableDescriptor descriptor = MetadataProperties.getDescriptor(callInfo.call);
            if (psiElement != null && descriptor != null) {
                trace.report(Errors.INLINE_CALL_CYCLE.on(psiElement, descriptor));
            }

            if (callInfo.containingFunction == calledFunction) {
                break;
            }
        }
    }

    private boolean hasToBeInlined(@NotNull JsInvocation call) {
        InlineStrategy strategy = MetadataProperties.getInlineStrategy(call);
        if (strategy == null || !strategy.isInline()) return false;

        return getFunctionContext().hasFunctionDefinition(call);
    }

    private class JsInliningContext implements InliningContext {
        private final FunctionContext functionContext;

        @NotNull
        private final JsContext<JsStatement> statementContextBeforeCurrentFunction;

        JsInliningContext(@NotNull JsContext<JsStatement> statementContextBeforeCurrentFunction) {
            functionContext = new FunctionContext(functionReader, config) {
                @Nullable
                @Override
                protected FunctionWithWrapper lookUpStaticFunction(@Nullable JsName functionName) {
                    return functions.get(functionName);
                }

                @Nullable
                @Override
                protected FunctionWithWrapper lookUpStaticFunctionByTag(@NotNull String functionTag) {
                    return accessors.get(functionTag);
                }
            };
            this.statementContextBeforeCurrentFunction = statementContextBeforeCurrentFunction;
        }

        @NotNull
        @Override
        public NamingContext newNamingContext() {
            return new NamingContext(getStatementContext());
        }

        @NotNull
        @Override
        public JsContext<JsStatement> getStatementContext() {
            return getLastStatementLevelContext();
        }

        @NotNull
        @Override
        public FunctionContext getFunctionContext() {
            return functionContext;
        }

        @NotNull
        @Override
        public JsContext<JsStatement> getStatementContextBeforeCurrentFunction() {
            return statementContextBeforeCurrentFunction;
        }
    }

    private static class JsCallInfo {
        @NotNull
        public final JsInvocation call;

        @NotNull
        public final JsFunction containingFunction;

        private JsCallInfo(@NotNull JsInvocation call, @NotNull JsFunction function) {
            this.call = call;
            containingFunction = function;
        }
    }
}

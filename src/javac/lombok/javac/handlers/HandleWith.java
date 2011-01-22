/*
 * Copyright © 2010-2011 Philipp Eichhorn
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import static lombok.javac.handlers.JavacTreeBuilder.*;
import static lombok.javac.handlers.JavacHandlerUtil.chainDots;
import static lombok.javac.handlers.JavacHandlerUtil.chainDotsString;
import java.util.Collection;

import org.mangosdk.spi.ProviderFor;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewArray;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

import lombok.javac.JavacASTAdapter;
import lombok.javac.JavacASTVisitor;
import lombok.javac.JavacNode;

@ProviderFor(JavacASTVisitor.class)
public class HandleWith extends JavacASTAdapter {
	private boolean handled;
	private String methodName;
	private int withVarCounter;
	
	@Override public void visitCompilationUnit(JavacNode top, JCCompilationUnit unit) {
		handled = false;
		withVarCounter = 0;
	}
	
	@Override public void visitStatement(JavacNode statementNode, JCTree statement) {
		if (statement instanceof JCMethodInvocation) {
			Collection<String> importedStatements = statementNode.getImportStatements();
			JCMethodInvocation methodCall = (JCMethodInvocation) statement;
			String name = methodCall.meth.toString();
			if (("lombok.With.with".equals(name))
					|| ("With.with".equals(name) && importedStatements.contains("lombok.With"))
					|| ("with".equals(name) && importedStatements.contains("lombok.With.with"))) {
				methodName = name;
				handled = handle(statementNode, methodCall);
			}
		}
	}
	
	@Override public void endVisitCompilationUnit(JavacNode top, JCCompilationUnit unit) {
		if (handled) {
			if ("with".equals(methodName)) {
				deleteImportFromCompilationUnit(top, "lombok.With.with", true);
			} else if ("With.with".equals(methodName)) {
				deleteImportFromCompilationUnit(top, "lombok.With");
			}
		}
	}
	
	public boolean handle(JavacNode methodCallNode, JCMethodInvocation withCall) {
		if (withCall.args.size() < 2) {
			return true;
		}
		
		TreeMaker maker = methodCallNode.getTreeMaker();
		ListBuffer<JCStatement> withCallStatements = ListBuffer.lb();
		JCExpression withExpr = withCall.args.head;
		String withExprName;
		if ((withExpr instanceof JCFieldAccess) || (withExpr instanceof JCIdent)) {
			withExprName = withExpr.toString();
		} else if (withExpr instanceof JCNewClass) {
			withExprName = "$with" + (withVarCounter++);
			withCallStatements.append(maker.VarDef(maker.Modifiers(Flags.FINAL), methodCallNode.toName(withExprName), ((JCNewClass)withExpr).clazz, withExpr));
			withExpr = chainDots(maker, methodCallNode, withExprName);
		} else {
			methodCallNode.addError("The first argument of 'with' can only be variable name or new-class statement.");
			return false;
		}
		JavacNode parent = methodCallNode.directUp();
		JCTree statementThatUsesWith = parent.get();
		boolean wasNoMethodCall = true;
		if ((statementThatUsesWith instanceof JCAssign) && ((JCAssign)statementThatUsesWith).rhs == withCall) {
			((JCAssign)statementThatUsesWith).rhs = withExpr;
		} else if (statementThatUsesWith instanceof JCFieldAccess) {
			((JCFieldAccess)statementThatUsesWith).selected = withExpr;
		} else if (statementThatUsesWith instanceof JCExpressionStatement) {
			((JCExpressionStatement)statementThatUsesWith).expr = withExpr;
			wasNoMethodCall = false;
		} else if (statementThatUsesWith instanceof JCVariableDecl) {
			((JCVariableDecl)statementThatUsesWith).init = withExpr;
		} else if (statementThatUsesWith instanceof JCReturn) {
			((JCReturn)statementThatUsesWith).expr = withExpr;
		} else if (statementThatUsesWith instanceof JCMethodInvocation) {
			JCMethodInvocation methodCall = (JCMethodInvocation)statementThatUsesWith;
			ListBuffer<JCExpression> newArgs = ListBuffer.lb();
			for (JCExpression arg : methodCall.args) {
				if (arg == withCall) newArgs.append(withExpr);
				else newArgs.append(arg);
			}
			methodCall.args = newArgs.toList();
		} else {
			methodCallNode.addError("'with' is not allowed here.");
			return false;
		}
		
		for (JCExpression arg : withCall.args.tail) {
			if (arg instanceof JCMethodInvocation) {
				arg = (JCExpression)arg.accept(new WithReferenceReplaceVisitor(methodCallNode, withExprName), null);
				withCallStatements.append(maker.Exec(arg));
			} else {
				methodCallNode.addError("Unsupported Expression in 'with': " + arg + ".");
				return false;
			}
		}
		
		while (!(statementThatUsesWith instanceof JCStatement)) {
			parent = parent.directUp();
			statementThatUsesWith = parent.get();
		}
		if (!(statementThatUsesWith instanceof JCStatement)) {
			// this would be odd odd but what the hell
			return false;
		}
		JavacNode grandParent = parent.directUp();
		JCTree block = grandParent.get();
		if (block instanceof JCBlock) {
			((JCBlock)block).stats = injectStatements(((JCBlock)block).stats, (JCStatement)statementThatUsesWith, wasNoMethodCall, withCallStatements.toList());
		} else if (block instanceof JCCase) {
			((JCCase)block).stats = injectStatements(((JCCase)block).stats, (JCStatement)statementThatUsesWith, wasNoMethodCall, withCallStatements.toList());
		} else if (block instanceof JCMethodDecl) {
			((JCMethodDecl)block).body.stats = injectStatements(((JCMethodDecl)block).body.stats, (JCStatement)statementThatUsesWith, wasNoMethodCall, withCallStatements.toList());
		} else {
			// this would be odd odd but what the hell
			return false;
		}
		
		grandParent.rebuild();
		
		return true;
	}
	
	private static List<JCStatement> injectStatements(List<JCStatement> statements, JCStatement statement, boolean wasNoMethodCall, List<JCStatement> withCallStatements) {
		final ListBuffer<JCStatement> newStatements = ListBuffer.lb();
		for (JCStatement stat : statements) {
			if (stat == statement) {
				newStatements.appendList(withCallStatements);
				if (wasNoMethodCall) newStatements.append(stat);
			} else newStatements.append(stat);
		}
		return newStatements.toList();
	}
	
	public static class WithReferenceReplaceVisitor extends TreeCopier<Void> {
		private final JavacNode node;
		private final TreeMaker maker;
		private final String withExprName;
		private boolean isMethodName;
		
		public WithReferenceReplaceVisitor(final JavacNode node, final String withExprName) {
			super(node.getTreeMaker());
			this.node = node;
			this.maker = node.getTreeMaker();
			this.withExprName = withExprName;
		}
		
		public JCTree visitNewArray(NewArrayTree node, Void p) {
			JCTree tree = super.visitNewArray(node, p);
			if (tree instanceof JCNewArray) {
				((JCNewArray)tree).elems = tryToReplace(((JCNewArray)tree).elems);
				((JCNewArray)tree).dims = tryToReplace(((JCNewArray)tree).dims);
			}
			return tree;
		}
		
		public JCTree visitNewClass(NewClassTree node, Void p) {
			JCTree tree = super.visitNewClass(node, p);
			if (tree instanceof JCNewClass) {
				((JCNewClass)tree).encl = tryToReplace(((JCNewClass)tree).encl);
				((JCNewClass)tree).args = tryToReplace(((JCNewClass)tree).args);
			}
			return tree;
		}
		
		public JCTree visitMethodInvocation(MethodInvocationTree node, Void p) {
			JCTree tree = super.visitMethodInvocation(node, p);
			if (tree instanceof JCMethodInvocation) {
				isMethodName = true;
				((JCMethodInvocation)tree).meth = tryToReplace(((JCMethodInvocation)tree).meth);
				isMethodName = false;
				((JCMethodInvocation)tree).args = tryToReplace(((JCMethodInvocation)tree).args);
			}
			return tree;
		}
		
		private List<JCExpression> tryToReplace(List<JCExpression> expressions) {
			ListBuffer<JCExpression> newExpr = ListBuffer.lb();
			for (JCExpression expr : expressions) {
				newExpr.append(tryToReplace(expr));
			}
			return newExpr.toList();
		}
		
		private JCExpression tryToReplace(JCExpression expr) {
			if (expr instanceof JCIdent) {
				String s = expr.toString();
				if ("_".equals(s)) return chainDotsString(maker, node, withExprName);
				if (!"this".equals(s) && isMethodName) return chainDotsString(maker, node, withExprName + "." + s);
			} else if (expr instanceof JCFieldAccess) {
				String[] s = expr.toString().split("\\.");
				if (s.length == 2) {
					if ("this".equals(s[0])) return chainDotsString(maker, node, s[1]);
					if ("_".equals(s[0])) return chainDotsString(maker, node, withExprName + "." + s[1]);
				}
			}
			return expr;
		}
	}
}
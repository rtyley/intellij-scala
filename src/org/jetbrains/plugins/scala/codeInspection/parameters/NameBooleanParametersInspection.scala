package org.jetbrains.plugins.scala
package codeInspection.parameters

import com.intellij.psi.PsiElementVisitor
import lang.psi.api.ScalaElementVisitor
import codeInspection.InspectionBundle
import com.intellij.codeInspection.{LocalInspectionTool, ProblemHighlightType, ProblemsHolder}
import lang.psi.api.base.ScLiteral
import lang.psi.api.expr.ScMethodCall

/**
 * @author Ksenia.Sautina
 * @since 5/10/12
 */

class NameBooleanParametersInspection extends LocalInspectionTool {
  override def isEnabledByDefault: Boolean = false

  override def buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = {
    new ScalaElementVisitor {
      override def visitMethodCallExpression(mc: ScMethodCall) {
        if (mc == null || mc.args == null || mc.args.exprs.isEmpty)
          return
        for (expr <- mc.args.exprs) {
          if (expr.isInstanceOf[ScLiteral] && (expr.getText == "true" || expr.getText == "false")) {
            holder.registerProblem(holder.getManager.createProblemDescriptor(expr,
              InspectionBundle.message("name.boolean"),
              new NameBooleanParametersQuickFix(mc, expr.asInstanceOf[ScLiteral]),
              ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly))
          }
        }
      }
    }
  }

}
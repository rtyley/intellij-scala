package org.jetbrains.plugins.scala
package codeInspection
package redundantReturnInspection

import org.intellij.lang.annotations.Language
import com.intellij.codeInspection._
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScFunctionDefinition}
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScReturnStmt}


class RemoveRedundantReturnInspection extends AbstractInspection("ScalaRedundantReturn", "Redundant Return") {

  def actionFor(holder: ProblemsHolder) = {
    case function: ScFunctionDefinition =>
    for (body <- function.body) {
        val returns = body.calculateReturns
        body.depthFirst(!_.isInstanceOf[ScFunction]).foreach {
          case r: ScReturnStmt =>
            if (returns.contains(r)) {
              r.expr match {
                case Some(expr) =>
                  holder.registerProblem(r.returnKeyword, "Return keyword is redundant",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new RemoveReturnKeywordQuickFix(r, expr))
                case _ =>
              }
            }
          case _ =>
        }
    }
  }
}

class RemoveReturnKeywordQuickFix(ret: ScReturnStmt, expr: ScExpression) extends LocalQuickFix {
  def applyFix(project: Project, descriptor: ProblemDescriptor) {
    ret.replace(expr.copy())
  }

  def getFamilyName: String = "Remove return keyword"

  def getName: String = getFamilyName
}
package org.jetbrains.plugins.scala.codeInspection.methodSignature

import com.intellij.codeInspection._
import org.intellij.lang.annotations.Language
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDeclaration
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScCompoundTypeElement
import quickfix.InsertMissingEquals

/**
 * Pavel Fatin
 */

class ApparentRefinementOfResultTypeInspection extends AbstractMethodSignatureInspection(
  "ScalaApparentRefinementOfResultType", "Apparent refinement of result type; are you missing an '=' sign?") {

  def actionFor(holder: ProblemsHolder) = {
    case f: ScFunctionDeclaration  => f.typeElement match {
      case Some(e: ScCompoundTypeElement) if e.refinement.isDefined =>
        holder.registerProblem(e, getDisplayName, new InsertMissingEquals(f))
      case _ =>
    }
  }
}

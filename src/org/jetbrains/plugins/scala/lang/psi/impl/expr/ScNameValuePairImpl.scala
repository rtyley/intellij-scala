package org.jetbrains.plugins.scala
package lang
package psi
package impl
package expr

import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElementImpl
import com.intellij.lang.ASTNode
import com.intellij.psi._
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import api.base.ScLiteral

/**
 * @author Alexander Podkhalyuzin
 */

class ScNameValuePairImpl(node: ASTNode) extends ScalaPsiElementImpl(node) with ScNameValuePair {
  override def toString: String = "NameValuePair"

  def setValue(newValue: PsiAnnotationMemberValue): PsiAnnotationMemberValue = newValue

  def getValue: PsiAnnotationMemberValue = null

  def getLiteral: Option[ScLiteral] = findChild(classOf[ScLiteral])

  def getLiteralValue: String = {
    getLiteral match {
      case Some(literal) =>
        val value = literal.getValue
        if (value != null) value.toString
        else null
      case _ => null
    }
  }
}
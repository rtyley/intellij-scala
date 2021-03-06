package org.jetbrains.plugins.scala
package lang
package psi
package api
package expr

import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElement
import org.jetbrains.plugins.scala.lang.psi.api.base._
import com.intellij.psi._
import psi.types.result.TypeResult
import psi.types.ScType
import psi.impl.ScalaPsiElementFactory
import extensions.toPsiNamedElementExt
import psi.types.nonvalue.TypeParameter
import lang.resolve.ScalaResolveResult

/** 
* @author Alexander Podkhalyuzin
* Date: 06.03.2008
*/

trait ScReferenceExpression extends ScalaPsiElement with ScExpression with ScReferenceElement {
  def isQualified = qualifier.isDefined

  def qualifier: Option[ScExpression] = getFirstChild match {case e: ScExpression => Some(e) case _ => None}

  protected var resolveFunction: () => Array[ResolveResult] = null

  protected var shapeResolveFunction: () => Array[ResolveResult] = null

  def setupResolveFunctions(resolveFunction: () => Array[ResolveResult], shapeResolveFunction: () => Array[ResolveResult]) {
    this.resolveFunction = resolveFunction
    this.shapeResolveFunction = shapeResolveFunction
  }

  /**
   * Includes qualifier for Infix, Postfix and Prefix expression
   * @return qualifier for Infix, Postfix, Prefix or reference expression
   */
  def smartQualifier: Option[ScExpression] = {
    qualifier match {
      case Some(qual) => Some(qual)
      case _ =>
        getParent match {
          case p: ScPrefixExpr if p.operation == this => Some(p.operand)
          case p: ScPostfixExpr if p.operation == this => Some(p.getBaseExpr)
          case p: ScInfixExpr if p.operation == this => Some(p.getBaseExpr)
          case _ => None
        }
    }
  }

  /**
   * This method returns all possible types for this place.
   * It's useful for expressions, which has two or more valid resolve results.
   * For example scala package, and scala package object.
   * Another usecase is when our type inference failed to decide to which method
   * we should resolve. If all methods has same result type, then we will give valid completion and resolve.
   */
  def multiType: Array[TypeResult[ScType]]

  def shapeMultiType: Array[TypeResult[ScType]]

  def shapeResolve: Array[ResolveResult]

  def shapeType: TypeResult[ScType]

  override def createReplacingElementWithClassName(useFullQualifiedName: Boolean, clazz: PsiClass) = {
    if (useFullQualifiedName) {
      super.createReplacingElementWithClassName(useFullQualifiedName, clazz)
    } else {
      ScalaPsiElementFactory.createExpressionFromText(clazz.name, clazz.getManager).asInstanceOf[ScReferenceExpression]
    }
  }

  def bindToElement(element: PsiElement, containingClass: Option[PsiClass]): PsiElement

  def getPrevTypeInfoParams: Seq[TypeParameter]
}

object ScReferenceExpression {
  object qualifier {
    def unapply(exp: ScReferenceExpression): Option[ScExpression] = exp.qualifier
  }
}
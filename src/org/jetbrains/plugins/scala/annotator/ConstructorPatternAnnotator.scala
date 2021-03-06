package org.jetbrains.plugins.scala
package annotator

import com.intellij.lang.annotation.AnnotationHolder
import lang.resolve.ScalaResolveResult
import lang.psi.api.statements.ScFunction
import lang.psi.types.{ScTupleType, ScParameterizedType, ScType}
import lang.psi.api.base.patterns.{ScPattern, ScConstructorPattern}
import lang.psi.types.result.TypeResult

/**
 * Jason Zaugg
 */

trait ConstructorPatternAnnotator {

  def annotateConstructorPattern(pattern: ScConstructorPattern, holder: AnnotationHolder, highlightErrors: Boolean) {
    if (highlightErrors) {
      pattern.ref.bind() match {
        case Some(ScalaResolveResult(element, subst)) =>
          val patternArgs = pattern.args.patterns
          element match {
            case fun: ScFunction if fun.name == "unapply" =>
              processUnapplyReturn(fun.returnType) {
                case Some(args) => checkArity(args.length, holder, pattern, patternArgs, isRepeated = false)
                case None =>
              }
            case fun: ScFunction if fun.name == "unapplySeq" =>
              processUnapplyReturn(fun.returnType) {
                case Some(args) => checkArity(args.length, holder, pattern, patternArgs, isRepeated = true)
                case None =>
              }
            case _ =>
          }
        case None =>
      }
    }
  }

  def processUnapplyReturn[A](unapplyReturn: TypeResult[ScType])(processArgs: Option[Seq[ScType]] => A): A = {
    unapplyReturn.flatMap(ScType.expandAliases).getOrAny match {
      case ScParameterizedType(des, Seq(argType))
        if Set("_root_.scala.Option", "_root_.scala.Some")(des.canonicalText) => //TODO better checking of base type.

        ScType.extractTupleType(argType) match {
          case Some(ScTupleType(components)) => processArgs(Some(components))
          case None => processArgs(Some(Seq(argType)))
        }
      case t if t.canonicalText == "Boolean" => processArgs(Some(Seq()))
      case _ => processArgs(None)
    }
  }

  def checkArity(numParams: Int, holder: AnnotationHolder, pattern: ScConstructorPattern,
                 patternArgs: scala.Seq[ScPattern], isRepeated: Boolean) {
    val numArgs = patternArgs.length
    val minParams = numParams - (if (isRepeated) 1 else 0)
    val maxParams = if (isRepeated) Int.MaxValue else numParams
    if (numArgs < minParams) {
      holder.createErrorAnnotation(pattern.args, "Missing arguments for " + pattern.ref.getText)
    } else if (patternArgs.length > maxParams) {
      patternArgs.drop(numParams).foreach {
        arg =>
          holder.createErrorAnnotation(arg, "Too many arguments for " + pattern.ref.getText)
      }
    }
  }
}
